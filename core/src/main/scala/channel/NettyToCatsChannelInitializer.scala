package cats.netty
package channel

import cats.data.{Ior, NonEmptyList}
import cats.effect.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._
import io.netty.channel.{Channel, ChannelHandler, ChannelInitializer}
import io.netty.util.AttributeKey

import cats.netty.NettySyntax._
import cats.netty.channel.NettyToCatsChannelInitializer.{OnNewConnection, PipelineMutation}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements"
  )
)
class NettyToCatsChannelInitializer[F[_]: Async, C <: Channel](
  dispatcher: Dispatcher[F],
  onNewConnection: OnNewConnection[F, C]
) extends ChannelInitializer[C] {

  private val inboundDispAttrKey =
    AttributeKey.valueOf[(Dispatcher[F], F[Unit])]("AllocatedInboundDispatcher")

  override def initChannel(ch: C): Unit = {
    // Disable auto-read for each individual connection
    ch.config().setAutoRead(false)

    dispatcher.unsafeRunAndForget {
      for {
        /*
         * Each channel gets their own ordered dispatcher, as IO events are sequential on a per
         * connection basis. This organization will becomes more important when CE runtime can
         * backpressure reads. Unordered, i.e. parallel, doesn't work in this case because we don't
         * want CE runtime to see reads, exceptions, and Netty event come out or order as it might
         * affect business logic (specifically read timeouts).
         */
        attr <- Async[F].delay(ch.attr(inboundDispAttrKey))

        allocatedInboundDisp <- Dispatcher.sequential[F](await = true).allocated

        _ <- Async[F].delay(attr.set(allocatedInboundDisp))

        handlers <- onNewConnection(ch)

        pipeline = ch.pipeline()

        _ <- handlers.pipelineMutations.traverse_ {
          case PipelineMutation.Add(handler) =>
            pipeline.addLastF(handler)

          case PipelineMutation.AddByName(name, handler) =>
            pipeline.addLastF(name, handler)
        }

        _ <- handlers.finalHandler.traverse_(pipeline.addLast(_))

        _ <- ch.setAutoRead(true)
      } yield ()
    }
  }

}

object NettyToCatsChannelInitializer {

  // TODO: Passing netty channel could break assumptions if caller manipulates pipeline or configs.
  //  Recreating channel class hierarchy to only expose safe methods is deferred for future work.
  type OnNewConnection[F[_], C <: Channel] = C => F[Handlers[F]]

  final case class Handlers[F[_]](
    value: Ior[NonEmptyList[PipelineMutation], ChannelHandlerF[F, _]]
  ) {
    def pipelineMutations: List[PipelineMutation] = value.left.toList.flatMap(_.toList)
    def finalHandler: Option[ChannelHandlerF[F, _]] = value.right
  }

  object Handlers {
    def apply[F[_]](
      mutations: NonEmptyList[PipelineMutation],
      handler: ChannelHandlerF[F, _]
    ): Handlers[F] = Handlers(Ior.both(mutations, handler))

    def apply[F[_]](
      mutations: List[PipelineMutation],
      handler: ChannelHandlerF[F, _]
    ): Handlers[F] =
      NonEmptyList.fromList(mutations).fold(fromHandler(handler))(apply(_, handler))

    def apply[F[_]](handler: ChannelHandler): Handlers[F] =
      apply(NonEmptyList.one(PipelineMutation.Add(handler)))

    def apply[F[_]](name: String, handler: ChannelHandler): Handlers[F] =
      apply(NonEmptyList.one(PipelineMutation.AddByName(name, handler)))

    def apply[F[_]](handler: ChannelHandlerF[F, _]): Handlers[F] =
      fromHandler(handler)

    def apply[F[_]](mutations: NonEmptyList[PipelineMutation]): Handlers[F] =
      fromPipelineMutations(mutations)

    def fromPipelineMutations[F[_]](mutations: NonEmptyList[PipelineMutation]): Handlers[F] =
      Handlers(Ior.left(mutations))

    def fromHandler[F[_]](handler: ChannelHandlerF[F, _]): Handlers[F] =
      Handlers(Ior.right(handler))
  }

  sealed abstract class PipelineMutation extends Product with Serializable

  object PipelineMutation {
    final case class Add(handler: ChannelHandler) extends PipelineMutation
    final case class AddByName(name: String, handler: ChannelHandler) extends PipelineMutation
  }
}
