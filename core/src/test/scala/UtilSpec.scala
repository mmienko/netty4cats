package cats.netty

import scala.concurrent.CancellationException
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.kernel.Outcome
import cats.effect.std.Supervisor
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.Promise

import cats.netty.Utils.fromNetty

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Null",
    "DisableSyntax.null",
    "org.wartremover.warts.Var",
    "DisableSyntax.var"
  )
)
class UtilSpec extends BaseSpec {

  private val channel = new EmbeddedChannel()
  private val testException = new Exception("a test error")

  "future as Effect" - {
    "default event loop promise behaves as expected" in {
      // verify underlying assumptions we in Utils.asEffect
      val p = newPromise[Object]()
      p.isDone shouldBe false
      p.isSuccess shouldBe false
      p.isCancelled shouldBe false
      p.isCancellable shouldBe true
      p.getNow shouldBe null
      p.cause() shouldBe null

      val succProm = newPromise[String]()
      succProm.setSuccess("yay").getNow shouldEqual "yay"
      succProm.isDone shouldBe true
      succProm.isSuccess shouldBe true
      succProm.isCancelled shouldBe false
      succProm.isCancellable shouldBe false
      succProm.cause() shouldBe null

      val failProm = newPromise[String]()
      failProm.setFailure(testException).getNow shouldEqual null
      failProm.isDone shouldBe true
      failProm.isSuccess shouldBe false
      failProm.isCancelled shouldBe false
      failProm.isCancellable shouldBe false
      failProm.cause() shouldBe testException

      val canProm = newPromise[String]()
      canProm.isCancelled shouldBe false
      canProm.cancel(true) shouldBe true
      canProm.isDone shouldBe true
      canProm.isSuccess shouldBe false
      canProm.isCancelled shouldBe true
      canProm.isCancellable shouldBe false
      canProm.getNow shouldBe null
      canProm.cause() shouldBe a[CancellationException]
      canProm.cancel(true) shouldBe false // idempotent

      val uncanProm = newPromise[String]()
      uncanProm.setUncancellable() shouldBe true
      uncanProm.isCancellable shouldBe false
      uncanProm.cancel(true) shouldBe false
      uncanProm.isDone shouldBe false
    }

    "complete future returns the effect" in runIO {
      for {
        // incomplete doesn't return
        res <- fromNetty[IO](IO(newPromise[Int]()))
          .timeoutTo(0.seconds, IO.pure(-1))
        _ <- IO(res shouldEqual -1)

        // complete will return
        p <- IO(newPromise[Int]())
        fiber <- fromNetty[IO](IO(p)).start

        _ <- IO(p.setSuccess(1))
        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Succeeded(fa) => fa.flatMap(res => IO(res shouldEqual 1))
          case _ => IO(fail("Expecting Succeed"))
        }
      } yield ()
    }

    "failed future invokes effect's error chanel" in runIO {
      for {
        p <- IO(newPromise[Int]())
        fiber <- fromNetty[IO](IO(p)).start

        _ <- IO(p.setFailure(testException))

        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Errored(e) => IO(e shouldEqual testException)
          case _ => IO(fail("Expecting Errored"))
        }
      } yield ()
    }

    "canceling effect cancels the future" in runIO {
      for {
        p <- IO(newPromise[Int]())

        /*
        Calling `cancel` immediately after `start` cancels the fiber before it even starts, so finalizers won't run.
        We could add a delay, but we'd realistically use higher level constructs like Supervisor.
         */
        fiber <- Supervisor[IO].use(_.supervise(fromNetty[IO](IO(p))))

        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Canceled() => IO.unit
          case _ => IO(fail("Expecting cancellation")).void
        }
        _ <- IO(p.isCancelled shouldBe true)
      } yield ()

    }

    "canceling future invokes effect's error channel" in runIO {
      for {
        p <- IO(newPromise[Int]())
        fiber <- fromNetty[IO](IO(p)).start

        _ <- IO(p.cancel(true) shouldBe true)

        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Errored(e) => IO(e shouldBe a[CancellationException])
          case _ => IO(fail("Expecting Errored of CancellationException"))
        }
      } yield ()
    }

    "cancellation on completed future is a no-op" in runIO {
      for {
        p <- IO(newPromise[Int]())
        fiber <- fromNetty[IO](IO(p)).start

        _ <- IO(p.setSuccess(1).sync())
        _ <- IO.sleep(50.millis)
        _ <- fiber.cancel

        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Succeeded(fa) => fa.flatMap(res => IO(res shouldEqual 1))
          case _ => IO(fail("Expecting Succeed"))
        }

        _ <- fiber.cancel
        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Succeeded(fa) => fa.flatMap(res => IO(res shouldEqual 1))
          case _ => IO(fail("Expecting Succeed"))
        }
      } yield ()
    }

    "uncancellable future cannot be cancelled from effect" in runIO {
      for {
        p <- IO(newPromise[Int]())
        uncancellable <- IO(p.setUncancellable())
        _ <- IO(uncancellable shouldBe true)

        fiber <- fromNetty[IO](IO(p)).start
        // cancel is itself uncancellable, thus promise needs to concurrently complete to finish cancel
        _ <- IO(p.setSuccess(1)).delayBy(200.millis)

        _ <- fiber.cancel

        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Succeeded(fa) => fa.flatMap(res => IO(res shouldEqual 1))
          case _ => IO(fail("Expecting Succeed"))
        }
      } yield ()
    }

    "uncancellable effect allows promise to finish" in runIO {
      for {
        p <- IO(newPromise[Int]())

        fiber <- fromNetty[IO](IO(p)).uncancelable.start
        // cancel is itself uncancellable, thus promise needs to concurrently complete to finish cancel
        _ <- IO(p.setSuccess(1)).delayBy(200.millis)

        _ <- fiber.cancel

        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Succeeded(fa) => fa.flatMap(res => IO(res shouldEqual 1))
          case _ => IO(fail("Expecting Succeed"))
        }
      } yield ()
    }

    "a cancelled promise with an uncancellable effect results in cancellation" in runIO {
      for {
        p <- IO(newPromise[Int]())

        fiber <- fromNetty[IO](IO(p)).uncancelable.start
        // cancel is itself uncancellable, thus promise needs to concurrently complete to finish cancel
        _ <- IO(p.cancel(false)).delayBy(200.millis)

        _ <- fiber.cancel

        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Errored(e) => IO(e shouldBe a[CancellationException])
          case _ => IO(fail("Expecting Errored of CancellationException"))
        }
      } yield ()
    }
  }

  private def newPromise[A](): Promise[A] = channel.eventLoop().newPromise[A]()

}
