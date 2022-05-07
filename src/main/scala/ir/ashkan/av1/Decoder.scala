package ir.ashkan.av1

import cats.{
  Defer,
  Show,
  Monad,
  FlatMap,
  Applicative,
  ApplicativeError,
  MonadError,
  Functor,
  Apply
}
import cats.syntax.all.*
import cats.data.{OptionT, EitherT}
import cats.effect.{Sync, IO, Concurrent}
import cats.effect.std.Console
import fs2.{Stream, Pull}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class Decoder[F[_], A](run: Stream[F, Byte] => Pull[F, Nothing, Decoder.Result[F, A]]) {
  def decode(bs: Stream[F, Byte])(using Concurrent[F]): F[Decoder.Result[F, A]] =
    run(bs).flatMap(Pull.output1).stream.compile.last.map(_.get)
}

object Decoder {
  enum Error {
    case EndOfStream
    case AssertionFailed[A](value: A, msg: String)
    case Unknown(message: String)
  }

  enum Result[+F[_], +A] {
    case Success(value: A, remainder: Stream[F, Byte], consumed: Int)
    case Failure(error: Error, consumed: Int)

    def addConsumed(prev: Int): Result[F, A] =
      this match {
        case Success(a, rest, c) => Success(a, rest, c + prev)
        case Failure(e, c) => Failure(e, c + prev)
      }
  }

  import Result.*
  import Error.*

  given [F[_]]: MonadError[Decoder[F, _], Error] with {
    override def flatMap[A, B](fa: Decoder[F, A])(f: A => Decoder[F, B]): Decoder[F, B] =
      Decoder(fa.run(_).flatMap {
        case Success(a, rest, c) => f(a).run(rest).map(_.addConsumed(c))
        case Failure(e, c) => Pull.pure(Failure(e, c))
      })

    override def map[A, B](fa: Decoder[F, A])(f: A => B): Decoder[F, B] =
      Decoder(fa.run(_).map {
        case Success(a, rest, c) => Success(f(a), rest, c)
        case Failure(e, c) => Failure(e, c)
      })

    override def tailRecM[A, B](a: A)(f: A => Decoder[F, Either[A, B]]): Decoder[F, B] = {
      def g(
          a: A,
          bs: Stream[F, Byte],
          c: Int
      ): Pull[F, Nothing, Either[(A, Stream[F, Byte], Int), Result[F, B]]] =
        f(a).run(bs).map {
          case Success(Right(b), r, c2) => Right(Success(b, r, c + c2))
          case Success(Left(a), r, c2) => Left((a, r, c + c2))
          case Failure(e, c2) => Right(Failure(e, c + c2))
        }

      Decoder[F, B]((a, _, 0).tailRecM(g))
    }

    override def pure[A](a: A): Decoder[F, A] = Decoder(Success(a, _, 0).pure)

    override def handleErrorWith[A](fa: Decoder[F, A])(
        f: Error => Decoder[F, A]): Decoder[F, A] =
      Decoder(bs =>
        fa.run(bs).flatMap {
          case Failure(e, c) => f(e).run(bs).map(_.addConsumed(c))
          case success => Pull.pure(success)
        })

    override def raiseError[A](e: Error): Decoder[F, A] = Decoder(_ => Pull.pure(Failure(e, 0)))
  }

  extension [F[_], A](fa: Decoder[F, A]) {
    def assert(p: A => Boolean, msg: => String = ""): Decoder[F, A] =
      assert(p, _ => msg)

    def assert(p: A => Boolean, msg: A => String): Decoder[F, A] =
      fa.ensureOr(a => AssertionFailed(a, msg(a)))(p)

    def const(c: A, msg: String = ""): Decoder[F, A] = fa.assert(_ == c, s"$msg (c = $c)")

    def collectOr[B](f: PartialFunction[A, B])(err: A => Error): Decoder[F, B] =
      fa.flatMap(a => f.lift(a).fold(err(a).raiseError)(_.pure))

    def withConsumed: Decoder[F, (A, Int)] =
      Decoder(bs =>
        fa.run(bs).map {
          case Success(a, rest, c) => Success((a, c), rest, c)
          case Failure(e, c) => Failure(e, c)
        })

    def consumeTo(size: Int): Decoder[F, A] = fa
      .withConsumed
      .flatMap((a, c) => (if c < size then drop8(size - c) else Decoder.void).as(a))

    def withOffset: Decoder[F, (A, Int)] = withConsumed.map((a, c) => (a, c - 1))

    def assertConsumed(expected: Int): Decoder[F, A] =
      withConsumed
        .assert(_._2 == expected, (_, c) => s"Consumed $c bytes instead of $expected bytes")
        .map(_._1)

    def when(f: Boolean): Decoder[F, Option[A]] =
      if f then fa.map(_.some) else Option.empty.pure
  }

  def eval[F[_], A](fa: F[A]): Decoder[F, A] =
    Decoder(bs => Pull.eval(fa).map(Success(_, bs, 0)))

  def println[F[_], A](a: A)(using c: Console[F])(using Show[A]): Decoder[F, Unit] =
    eval(c.println(a))

  def print[F[_], A](a: A)(using c: Console[F])(using Show[A]): Decoder[F, Unit] =
    eval(c.print(a))

  def void[F[_]]: Decoder[F, Unit] = ().pure[Decoder[F, _]]

  def uint8[F[_]]: Decoder[F, Byte] = Decoder(_.pull.uncons1.map {
    case Some((a, remainder)) => Success(a, remainder, 1)
    case None => Failure(Error.EndOfStream, 0)
  })

  def char8[F[_]]: Decoder[F, Char] = uint8.map(_.toChar)

  enum Endianness(
      val uint16: (Byte, Byte) => Short,
      val uint32: (Byte, Byte, Byte, Byte) => Int,
      val uint64: (Int, Int) => Long
  ) {
    case Little
        extends Endianness(
          (b0, b1) => ((b0 & 0xff) + ((b1 & 0xff) << 8)).toShort,
          (b0, b1, b2, b3) =>
            (b0 & 0xff) + ((b1 & 0xff) << 8) + ((b2 & 0xff) << 16) + ((b3 & 0xff) << 24),
          (i0, i1) => (i0 & 0xffff_ffff).toLong + ((i1 & 0xffff_ffff).toLong << 32)
        )
    case Big
        extends Endianness(
          (b0, b1) => Little.uint16(b1, b0),
          (b0, b1, b2, b3) => Little.uint32(b3, b2, b1, b0),
          (i0, i1) => Little.uint64(i1, i0)
        )
  }

  def uint16[F[_]](using end: Endianness): Decoder[F, Short] =
    for
      b0 <- uint8
      b1 <- uint8
    yield end.uint16(b0, b1)

  def uint32[F[_]](using end: Endianness): Decoder[F, Int] =
    for
      b0 <- uint8
      b1 <- uint8
      b2 <- uint8
      b3 <- uint8
    yield end.uint32(b0, b1, b2, b3)

  def uint64[F[_]](using end: Endianness): Decoder[F, Long] =
    for
      int0 <- uint32
      int1 <- uint32
    yield end.uint64(int0, int1)

  enum IntSize[Out] {
    case _4 extends IntSize[(Int, Int)]
    case _8 extends IntSize[Byte]
    case _16 extends IntSize[Short]
    case _32 extends IntSize[Int]
    case _64 extends IntSize[Long]
  }

  object IntSize {
    def fromSize(size: Int): Option[IntSize[?]] =
      size match {
        case 4 => Some(_4)
        case 8 => Some(_8)
        case 16 => Some(_16)
        case 32 => Some(_32)
        case 64 => Some(_64)
        case _ => None
      }
    // def decode4[F[_]]: Decoder[F, (IntSize[?], Byte)] = uin
    // def decode8[F[_]]: Decoder[F, IntSize[?]] = uint8.
  }

  def uint[F[_], Out](size: IntSize[Out])(using Endianness): Decoder[F, Out] =
    size match {
      case IntSize._4 => uint4x2[F]
      case IntSize._8 => uint8[F]
      case IntSize._16 => uint16[F]
      case IntSize._32 => uint32[F]
      case IntSize._64 => uint64[F]
    }

  def cstr[F[_]]: Decoder[F, String] = {
    def go(s: StringBuilder): Decoder[F, StringBuilder] =
      uint8.flatMap(b => if b != 0 then go(s.addOne(b.toChar)) else s.pure)

    go(new StringBuilder).map(_.toString)
  }

  def uint4x2[F[_]]: Decoder[F, (Int, Int)] = uint8.map(b => (b & 0xf0, b & 0x0f))

  def drop8[F[_]](n: Int): Decoder[F, Unit] = if n == 0 then void else uint8 >> drop8(n - 1)

  def drop16[F[_]](n: Int): Decoder[F, Unit] = drop8(n * 2)

  def drop32[F[_]](n: Int): Decoder[F, Unit] = drop8(n * 4)

  final def prefix[F[_]](cs: List[Byte]): Decoder[F, Unit] = cs match
    case c :: rest => uint8.const(c, "prefix") >> prefix(rest)
    case Nil => void

  def prefix[F[_]](s: String): Decoder[F, Unit] = prefix(s.toList.map(_.toByte))

  def fail[F[_], A](e: Error): Decoder[F, A] = e.raiseError
  def fail[F[_], A](msg: String): Decoder[F, A] = fail(Unknown(msg))

  def pure[F[_]]: [A] => A => Decoder[F, A] = [A] =>
    (a: A) => summon[Applicative[Decoder[F, _]]].pure[A](a)
}
