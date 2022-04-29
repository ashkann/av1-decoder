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
    def assert(p: A => Boolean, msg: String = ""): Decoder[F, A] =
      fa.ensureOr(AssertionFailed(_, msg))(p)

    def const(c: A, msg: String = ""): Decoder[F, A] = fa.assert(_ == c, s"$msg (c = $c)")

    def collectOr[B](f: PartialFunction[A, B])(err: A => Error): Decoder[F, B] =
      fa.flatMap(a => f.lift(a).fold(err(a).raiseError)(_.pure))

    def withConsumed: Decoder[F, (A, Int)] =
      Decoder(bs =>
        fa.run(bs).map {
          case Success(a, rest, c) => Success((a, c), rest, c)
          case Failure(e, c) => Failure(e, c)
        })
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

  def uint16[F[_]]: Decoder[F, Short] =
    for
      b0 <- uint8
      b1 <- uint8
    yield ((b0 & 0xff) + ((b1 & 0xff) << 8)).toShort

  def uint32[F[_]]: Decoder[F, Int] =
    for
      b0 <- uint8
      b1 <- uint8
      b2 <- uint8
      b3 <- uint8
    yield (b0 & 0xff) + ((b1 & 0xff) << 8) + ((b2 & 0xff) << 16) + ((b3 & 0xff) << 24)

  def drop8[F[_]](n: Int): Decoder[F, Unit] = if n == 0 then void else uint8 >> drop8(n - 1)

  def drop16[F[_]](n: Int): Decoder[F, Unit] = drop8(n * 2)

  def drop32[F[_]](n: Int): Decoder[F, Unit] = drop8(n * 4)

  final def prefix[F[_]](cs: List[Byte]): Decoder[F, Unit] = cs match
    case c :: rest => uint8.const(c, "prefix") >> prefix(rest)
    case Nil => void

  def prefix[F[_]](s: String): Decoder[F, Unit] = prefix(s.toList.map(_.toByte))

  def fail[F[_], A](e: Error): Decoder[F, A] = e.raiseError
}
