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
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.Builder

object Avf1 extends IOApp.Simple {
  import Decoder.*
  import Decoder.Error.*
  import Decoder.Result.*

  given Endianness = Endianness.Big

  case class RGB(r: Byte, g: Byte, b: Byte) {
    val raw = Raw(r, g, b)
  }

  case class Raw(y: Byte, cr: Byte, cb: Byte) {
    var bytes = Stream(y, cr, cb)
  }

  given Show[RGB] = Show.show(t => "%02X%02X%02X".format(t.r, t.g, t.b))

  type FourCC = (Byte, Byte, Byte, Byte)
  extension (fcc: FourCC) {
    def str = "%c%c%c%c".format(fcc._1, fcc._2, fcc._3, fcc._4)
  }
  def fourcc[F[_]]: Decoder[F, FourCC] = (uint8, uint8, uint8, uint8).mapN((_, _, _, _))
  given Show[FourCC] = Show.show(_.str)
  // "%s (%02X,%02X,%02X,%02X)".format(fcc.str, fcc._4, fcc._1, fcc._2, fcc._3, fcc._4))
  given Show[List[FourCC]] = Show.show(_.map(_.show).mkString(", "))

  case class BoxHeader(size: Int, fourcc: FourCC, version: Int, flags: Int)

  given Show[BoxHeader] =
    Show.show(b =>
      show"${b.fourcc}, size ${b.size}, version ${b.version}, flags ${b.flags.toBinaryString}")

  def fileType[F[_]: Console]: Decoder[F, Unit] = for
    size <- uint32
    ftyp <- fourcc.map(_.str).const("ftyp", "ftyp box")
    _ <- println(show"$ftyp box, size = $size")

    majorBrand <- fourcc.map(_.str).const("avif", "major brand")
    _ <- println(show"Major brand: $majorBrand")

    minorVersion <- uint32
    remaining = size - (4 + 4 + 4 + 4)
    minorBrands <-
      if remaining % 4 == 0 then fourcc.replicateA(remaining / 4)
      else
        Decoder.fail(
          s"Number of bytes for minor brands ($remaining) in ftyp is should be a multiple of 4.")
    _ <- println(show"Minor brands: $minorBrands")
  yield ()

  def handlerTypeDefinition[F[_]: Console]: Decoder[F, Unit] =
    for
      _ <- uint32.const(0, "hdlr pre_defined")
      _ <- fourcc.map(_.str).const("pict")
      _ <- uint32.const(0).replicateA(3)
      _ <- cstr.flatTap(println)
    yield ()

  def box[F[_]: Console, A](payload: BoxHeader => Decoder[F, A]): Decoder[F, A] =
    boxHeader
      .flatTap(println)
      .withConsumed
      .flatMap((h, c) => payload(h).assertConsumed(h.size - c))

  def ignore[F[_]: Console](header: BoxHeader): Decoder[F, Unit] = drop8(header.size - 12)

  def metaData[F[_]: Console](size: Int): Decoder[F, Unit] = {
    def itemLocation[F[_]: Console]: Decoder[F, Unit] =
      for
        (offsetSize, lengthSize) <- uint8.map(b => (b & 0xF0, b & 0x0F))
        _ <- println((offsetSize, lengthSize))
      yield ()

    val items = Map[String, BoxHeader => Decoder[F, Unit]](
      "iloc" -> (h => itemLocation[F].consumeTo(h.size - 12)),
      "pitm" -> ignore[F],
      "idat" -> ignore[F],
      "iprp" -> ignore[F],
      "iinf" -> ignore[F],
      "iref" -> ignore[F]
    )

    def go(codes: Map[String, BoxHeader => Decoder[F, Unit]])
        : Decoder[F, Map[String, BoxHeader => Decoder[F, Unit]]] =
      if codes.nonEmpty then
        box(h =>
          codes
            .getOrElse(h.fourcc.str, _ => Decoder.fail(show"Unexpected box type: $h"))(h)
            .as(codes - h.fourcc.str)).flatMap(go)
      else Map.empty.pure

    for
      _ <- box(_ => handlerTypeDefinition)
      _ <- go(items)
    yield ()
  }

  def mediaData[F[_]](size: Int): Decoder[F, Unit] = Decoder.void

  def boxHeader[F[_]]: Decoder[F, BoxHeader] =
    for
      size <- uint32
      fcc <- fourcc
      version <- uint8
      flags <- (uint8, uint8, uint8).mapN(summon[Endianness].uint32(_, _, _, 0))
    yield BoxHeader(size, fcc, version, flags)

  def avif[F[_]: Console: Concurrent]: Decoder[F, ArraySeq[RGB]] =
    for
      _ <- fileType
      _ <- boxHeader.flatMap {
        case h @ BoxHeader(size, fcc, _, _) =>
          for
            _ <- println(h.show)
            _ <- (fcc.str match {
              case "meta" =>
                metaData(size).withOffset.flatMap((_, offset) => drop8(offset - 12))
              case "mdat" =>
                mediaData(size).withOffset.flatMap((_, offset) => drop8(offset - 12))
              case _ => drop8(size - 12)
            })
          yield ()
      }.foreverM
    yield ArraySeq.empty[RGB]
  end avif

  val in = Files[IO].readAll(Path("images/fox.profile0.10bpc.yuv420.avif"))
  val out = Files[IO].writeAll(Path("raw.ycrcb"))
  val run =
    for _ <- avif[IO].decode(in).flatMap {
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