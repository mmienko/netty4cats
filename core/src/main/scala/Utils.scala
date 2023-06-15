package cats.netty

import cats.effect.kernel.Async
import cats.syntax.all._
import io.netty.channel.ChannelFuture
import io.netty.util.concurrent.Future

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements"
  )
)
object Utils {

  /**
    * Transforms a Netty [[io.netty.util.concurrent.Future]] into an Effect. The Effect semantically
    * waits until for Future to complete. Cancellation is supported.
    * @tparam F
    * @return
    */
  private[netty] def fromNetty[F[_]]: PartiallyApplied[F] = new PartiallyApplied[F]

  /*
  The purpose of using a class instead of a direct method is to allow subtyping of Future in call sites. A method like
  ```
  def fromNetty[F[_]: Async, A](future: F[Future[A]]): F[A] = ???
  ```
  would need equivalently named methods for subtypes like Promise, ChannelFuture, etc. Each needs a unique name b/c of
  erasure.
   */
  private[netty] class PartiallyApplied[F[_]] {
    // this only needs to exist because the Scala 2 compiler is really bad at subtyping.
    // DummyImplicit exists to avoid double definition compiler error.
    def apply(cf: F[ChannelFuture])(implicit F: Async[F], D: DummyImplicit): F[Void] =
      apply[Void](cf.widen)

    def apply[A](ff: F[Future[A]])(implicit F: Async[F]): F[A] =
      ff.flatMap { future =>
        val effect = F.async[A] { cb =>
          F.delay {
            future.addListener((f: Future[A]) => {
              if (f.isSuccess)
                cb(f.getNow.asRight[Throwable])
              else
                cb(f.cause().asLeft[A])
            })

            // The boolean value doesn't matter to Netty; it's part of Java interface.
            F.whenA(future.isCancellable)(F.delay(future.cancel(true))).some
          }
        }

        if (future.isCancellable) effect else F.uncancelable(_ => effect)
      }
  }

  /**
    * Function that allows values to be discarded in a visible way.
    */
  object ValueDiscard {

    /**
      * Function that allows values to be discarded in a visible way.
      *
      * @tparam A
      *   type of the value that will be computed (it won't be inferred, must be specified)
      * @return
      *   function accepting value expression that needs to be computed and whose value will be
      *   discarded
      */
    def apply[A]: (=> A) => Unit = { value =>
      val _ = value
    }
  }

}
