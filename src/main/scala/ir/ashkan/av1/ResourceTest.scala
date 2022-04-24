package ir.ashkan.av1

import cats.effect.kernel.Resource
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
import cats.effect.std.Queue
import cats.data.OptionT
import cats.syntax.all.*
import cats.instances.all.*
import cats.Traverse
import cats.Monad
import cats.effect.kernel.Deferred
import cats.effect.ExitCode

object ResourceTest extends IOApp.Simple {
    def split[F[_]: Concurrent, A](
        in: Stream[F, A]
    )(p: A => Boolean): F[(Stream[F, A], Stream[F, A])] =
        for
            left  <- Queue.unbounded[F, Option[A]]
            right <- Queue.unbounded[F, Option[A]]
            _ <- in
                .foreach(a => if (p(a)) left.offer(Some(a)) else right.offer(Some(a)))
                .compile
                .drain
            _ <- left.offer(None)
            _ <- right.offer(None)
        yield (Stream.fromQueueNoneTerminated(left), Stream.fromQueueNoneTerminated(right))

    def split[F[_]: Concurrent, A](in: Stream[F, A], size: Long): F[(Stream[F, A], Stream[F, A])] =
        for
            before  <- Queue.unbounded[F, Option[A]]
            after <- Queue.unbounded[F, Option[A]]
            _ <- in
                .compile
                .drain
            _ <- before.offer(None)
            _ <- after.offer(None)
        yield (Stream.fromQueueNoneTerminated(before), Stream.fromQueueNoneTerminated(after))
        
    val run = {
        val is = Stream.emits(1 to 10)
        for
            (evens, odds) <- split[IO, Int](is)(_ % 2 == 0)
            _             <- evens.foreach(IO.println).compile.drain // >> IO.println("")
            _             <- odds.foreach(IO.println).compile.drain
        yield ()
    }
}
