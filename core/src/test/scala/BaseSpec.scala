package cats.netty

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

trait BaseSpec
    extends AnyFreeSpec
    with Matchers
    with EitherValues
    with OptionValues
    with TryValues
    with GivenWhenThen {

  protected def runIO[A](f: => IO[A]): Unit =
    f.void.unsafeRunSync()

}
