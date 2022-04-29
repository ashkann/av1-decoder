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
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.Builder

object ReadBmp extends IOApp.Simple {
  import Decoder.*
  import Decoder.Error.*
  import Decoder.Result.*

  case class RGB(r: Byte, g: Byte, b: Byte) {
    val raw = Raw(r, g, b)
  }

  case class Raw(y: Byte, cr: Byte, cb: Byte) {
    var bytes = Stream(y, cr, cb)
  }

  given Show[RGB] = Show.show(t => "%02X%02X%02X".format(t.r, t.g, t.b))

  enum ColorDepth(val bits: Short) {
    case `24` extends ColorDepth(24)
    case `32` extends ColorDepth(32)

    def pixel[F[_]]: Decoder[F, RGB] = this match {
      case `24` => (uint8, uint8, uint8).mapN((b, g, r) => RGB(r, g, b))
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

  def bmpToRaw[F[_]: Console: Concurrent]: Decoder[F, ArraySeq[RGB]] =
    val BitmapInfoHeader: Decoder[F, (Int, Int, ColorDepth)] =
      for
        width <- uint32.assert(_ >= 0, "width")
        height <- uint32.map(_.abs).assert(_ >= 0, "height")
        _ <- uint16.const(1) // planes
        depth <- ColorDepth.dec
        _ <- uint32.const(0, "compression") // compression
        _ <- drop32(3) // image size, X and Y pixel per meter
        _ <- uint32.const(0, "colors")
        _ <- uint32.const(0, "important colors")
      yield (width, height, depth)

    val BitmapV5Header: Decoder[F, (Int, Int, ColorDepth)] =
      for
        width <- uint32.assert(_ >= 0, "width")
        height <- uint32.map(_.abs).assert(_ >= 0, "height")
        _ <- uint16.const(1) // planes
        depth <- ColorDepth.dec
        _ <- uint32.const(0, "compression") // compression
        _ <- drop32(1) // image size
        _ <- drop32(2) // X and Y pixel per meter
        _ <- uint32.const(0, "colors")
        _ <- uint32.const(0, "important colors")
        _ <- drop32(4) // R, G, B and A masks
        _ <- drop32(1) // color space type
        _ <- drop8(36) // color space endpoints
        _ <- drop32(3) // Gamma for R,G and B
        _ <- drop32(1) // intent
        _ <- drop32(2) // ICC profile
        _ <- drop32(1) // Reserved
      yield (width, height, depth)

    val dib: Decoder[F, (Int, Int, ColorDepth)] =
      for
        size <- uint32
        dib <- size match {
          case 40 => BitmapInfoHeader
          case 124 => BitmapV5Header
          case other => Decoder.fail(Unknown(s"Unsupported header size: $other"))
        }
      yield dib

    val header =
      for
        _ <- prefix[F]("BM")
        _ <- drop32(1) // file size
        _ <- drop16(2) // reserved 1, reserved 2
        offset <- uint32 // file offset to pixel array
      yield offset

    for {
      (offset, s1) <- header.withConsumed 
      ((w, h, bpp), s2) <- dib.withConsumed
      _ <- println(s"Header size: $s1, DIB size: $s2, Offset: $offset")
      _ <- println(s"${w}x${h} pixels, Color depth: $bpp")
      _ <- drop8(offset - s1 - s2)
      pixels <- readImageData(w, h, bpp)(ArraySeq.newBuilder[RGB])
    } yield pixels
  end bmpToRaw

  def readImageData[F[_]: Concurrent](
      width: Int,
      height: Int,
      format: ColorDepth
  ): [C[_]] => Builder[RGB, C[RGB]] => Decoder[F, C[RGB]] =
    [C[_]] =>
      (acc: Builder[RGB, C[RGB]]) => {
        val n = width * format.bytes
        val pad = if (n % 4 == 0) 0 else 4 - (n % 4)
        val pixel = format.pixel[F]

        def row(acc: Builder[RGB, C[RGB]]) =
          (acc, 0)
            .iterateWhileM { (acc, x) => pixel.map(acc.addOne(_) -> (x + 1)) }(_._2 < width)
            .map(_._1) <* drop8(pad)

        val pixels =
          val r0 = row(acc)
          if height == 1 then r0
          else
            r0.flatMap { acc =>
              (acc, 0).iterateWhileM((ps, y) => row(ps).map((_, y + 1)))(_._2 < height - 1)
            }.map(_._1)
        end pixels

        pixels.map(_.result)
    }

  val in = Files[IO].readAll(Path("24bit.bmp"))
  val out = Files[IO].writeAll(Path("raw.ycrcb"))
  val run =
    for _ <- bmpToRaw[IO].decode(in).flatMap {
        case Decoder.Result.Success(raws, _, c) =>
          Stream
            .fromIterator[IO](raws.iterator, 10)
            .flatMap(_.raw.bytes)
            .through(out)
            .compile
            .drain >> Console[IO].println(s"Read $c bytes. Done")

        case Decoder.Result.Failure(e, c) => Console[IO].println(s"Read $c bytes. $e")
      } yield ()
}
