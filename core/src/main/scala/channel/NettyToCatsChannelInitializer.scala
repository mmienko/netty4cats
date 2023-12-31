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
    AttributeKey.valueOf[Dispatcher[F]]("InboundDispatcher")

  override def initChannel(ch: C): Unit = {
    // Disable auto-read for each individual connection
    ch.config().setAutoRead(false)

    dispatcher.unsafeRunAndForget {
      for {
        attr <- Async[F].delay(ch.attr(inboundDispAttrKey))
        /*
         * Each channel gets their own sequential dispatcher to mirror order of Netty events in handler. Netty's
         * threading model ensures handler has single threaded semantics for Netty events (handler methods).
         * We ignore Dispatcher's finalizer in `await=true` mode, since all it does is:
         *  - shutdown the dispatcher
         *  - join all fibers (in supervisor)
         * Ignore finalizer from Dispatcher resource as all it does is wait for fibers to join. This is effectively
         * a no-op since we never cancel fibers (await=true). This aligns with Netty's ChannelHandler model, which
         * offers single threaded semantics with no cancellation. The dispatcher is analogous to Java's ExecutorService,
         * where we simply submit tasks and forget. Dispatcher keeps track of Fibers via Supervisor, but
         * cleanup after themselves when they are finished. This is another reason why higher layers should process
         * Netty ChannelHandler actions in a semantically non-blocking way (to be able to respond to newer Netty
         * events).
         */
        inboundDisp <- Dispatcher.sequential[F](await = true).allocated.map(_._1)

        _ <- Async[F].delay(attr.set(inboundDisp))

        handlers <- onNewConnection(ch)

        pipeline = ch.pipeline()

        _ <- handlers.pipelineMutations.traverse_ {
          case PipelineMutation.Add(handler) =>
            pipeline.addLastF(handler)

          case PipelineMutation.AddByName(name, handler) =>
            pipeline.addLastF(name, handler)
        }

        _ <- handlers.finalHandler.traverse_(pipeline.addLastF(_))

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
    value: Ior[NonEmptyList[PipelineMutation], NettyToCatsEffectRuntimeHandler[F, _]]
  ) {
    def pipelineMutations: List[PipelineMutation] = value.left.toList.flatMap(_.toList)
    def finalHandler: Option[NettyToCatsEffectRuntimeHandler[F, _]] = value.right
  }

  object Handlers {
    def apply[F[_]](
      mutations: NonEmptyList[PipelineMutation],
      handler: NettyToCatsEffectRuntimeHandler[F, _]
    ): Handlers[F] = Handlers(Ior.both(mutations, handler))

    def apply[F[_]](
      mutations: List[PipelineMutation],
      handler: NettyToCatsEffectRuntimeHandler[F, _]
    ): Handlers[F] =
      NonEmptyList.fromList(mutations).fold(fromHandler(handler))(apply(_, handler))

    def apply[F[_]](handler: ChannelHandler): Handlers[F] =
      apply(NonEmptyList.one(PipelineMutation.Add(handler)))

    def apply[F[_]](name: String, handler: ChannelHandler): Handlers[F] =
      apply(NonEmptyList.one(PipelineMutation.AddByName(name, handler)))

    def apply[F[_]](handler: NettyToCatsEffectRuntimeHandler[F, _]): Handlers[F] =
      fromHandler(handler)

    def apply[F[_]](mutations: NonEmptyList[PipelineMutation]): Handlers[F] =
      fromPipelineMutations(mutations)

    def fromPipelineMutations[F[_]](mutations: NonEmptyList[PipelineMutation]): Handlers[F] =
      Handlers(Ior.left(mutations))

    def fromHandler[F[_]](handler: NettyToCatsEffectRuntimeHandler[F, _]): Handlers[F] =
      Handlers(Ior.right(handler))
  }

  sealed abstract class PipelineMutation extends Product with Serializable

  object PipelineMutation {
    final case class Add(handler: ChannelHandler) extends PipelineMutation
    final case class AddByName(name: String, handler: ChannelHandler) extends PipelineMutation
  }
}
