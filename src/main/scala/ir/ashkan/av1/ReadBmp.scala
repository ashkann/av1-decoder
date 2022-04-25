package ir.ashkan.av1

import fs2.*
import fs2.io.net.*
import fs2.{hash, text}
import fs2.io.file.{Files, Path}
import fs2.Pull.*
import fs2.Pull
import cats.Show
import cats.effect.{IOApp, IO, Concurrent, Sync}
import cats.effect.unsafe.implicits.global
import cats.effect.std.Console
import cats.data.OptionT
import cats.syntax.all.*
import cats.instances.all.*
import cats.Traverse
import cats.Monad
import cats.effect.kernel.Deferred

object ReadBmp extends IOApp.Simple {
    import Decoder.*
    import Decoder.Error.*
    import Decoder.Result.*

    case class Color(r: Byte, g: Byte, b: Byte) {
        val raw = Stream(r, g, b)
    }

    given Show[Color] = Show.show(t => "%02X%02X%02X".format(t.r, t.g, t.b))

    enum ColorDepth(val bits: Short) {
        case `24` extends ColorDepth(24)
        case `32` extends ColorDepth(32)

        def pixel[F[_]]: Decoder[F, Color] = this match {
            case `24` => (uint8, uint8, uint8).mapN((b, g, r) => Color(r, g, b))
            case `32` => `24`.pixel <* drop8(1)
        }

        val bytes = bits / 8
    }

    object ColorDepth {
        def dec[F[_]]: Decoder[F, ColorDepth] =
            uint16.collectOr {
                case 24 => `24`
                case 32 => `32`
            }(bits => Unknown(s"Unsupported color depth: $bits"))
    }
    given Show[ColorDepth] = Show.show(bpp => s"Color depth: ${bpp.bits} bits")

    def bmpToRaw[F[_]: Console: Concurrent]: Decoder[F, Stream[F, Byte]] =
        val BitmapInfoHeader: Decoder[F, (Int, Int, ColorDepth)] =
            for
                width  <- uint32.assert(_ >= 0, "width")
                height <- uint32.map(_.abs).assert(_ >= 0, "height")
                _      <- uint16.const(1)                // planes
                depth  <- ColorDepth.dec
                _      <- uint32.const(0, "compression") // compression
                _      <- drop32(3)                      // image size, X and Y pixel per meter
                _      <- uint32.const(0, "colors")
                _      <- uint32.const(0, "important colors")
            yield (width, height, depth)

        val BitmapV5Header: Decoder[F, (Int, Int, ColorDepth)] =
            for
                width  <- uint32.assert(_ >= 0, "width")
                height <- uint32.map(_.abs).assert(_ >= 0, "height")
                _      <- uint16.const(1)                // planes
                depth  <- ColorDepth.dec
                _      <- uint32.const(0, "compression") // compression
                _      <- drop32(1)                      // image size
                _      <- drop32(2)                      // X and Y pixel per meter
                _      <- uint32.const(0, "colors")
                _      <- uint32.const(0, "important colors")
                _      <- drop32(4)                      // R, G, B and A masks
                _      <- drop32(1)                      // color space type
                _      <- drop8(36)                      // color space endpoints
                _      <- drop32(3)                      // Gamma for R,G and B
                _      <- drop32(1)                      // intent
                _      <- drop32(2)                      // ICC profile
                _      <- drop32(1)                      // Reserved
            yield (width, height, depth)

        val header: Decoder[F, (Int, Int, ColorDepth)] =
            uint32.flatMap {
                case 124  => BitmapInfoHeader
                case 40   => BitmapV5Header
                case size => Unknown(s"Unsupported header size: $size").raiseError
            }

        for {
            _           <- prefix[F]("BM")
            _           <- drop32(1) // file size
            _           <- drop16(2) // reserved 1, reserved 2
            _           <- drop32(1) // file offset to pixel array
            (w, h, bpp) <- header
            _           <- println(s"${w}x${h} pixels, Color depth: $bpp")
            colors      <- readImageData(w, h, bpp)
        } yield colors.flatMap(_.raw)
    end bmpToRaw

    // def toStreamAndResult[F[_]: Concurrent, O, R](pull: Pull[F, O, R]): F[(Stream[F, O], F[R])] =
    //     Deferred[F, R].map { result =>
    //         (pull.flatMap(r => Pull.eval(result.complete(r).void)).stream, result.get)
    //     }

    def readImageData[F[_]: Concurrent](
        width: Int,
        height: Int,
        format: ColorDepth
    ): Decoder[F, Array[Byte]] = {
        val row = width * format.bytes
        val pad = if (row % 4 == 0) 0 else 4 - (row % 4)
        val p   = format.pixel[F]
        val p2  = p <* drop8(pad)

        take( (row + pad) * height * format.bytes)
    }

    val in  = Files[IO].readAll(Path("24bit.bmp"))
    val out = Files[IO].writeAll(Path("raw.ycrcb"))
    val run =
        for _ <- bmpToRaw[IO].decode(in).flatMap {
                case Decoder.Result.Success(bytes, _) =>
                    bytes.through(out).compile.drain >> Console[IO].println("Done")
                case Decoder.Result.Failure(e) => Console[IO].println(e)
            }
        yield ()
}
