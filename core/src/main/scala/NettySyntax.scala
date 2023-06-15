package cats.netty

import cats.effect.{Async, Sync}
import cats.syntax.all._
import io.netty.channel._

import cats.netty.channel.{ChannelHandlerF, NettyToCatsEffectRuntimeHandler}

object NettySyntax {

  implicit class ChannelHandlerContextOps[F[_]](ctx: ChannelHandlerContext)(implicit F: Async[F]) {
    def setAutoRead(autoRead: Boolean): F[Unit] =
      ctx.channel().setAutoRead(autoRead)

    def removeHandler[H <: ChannelHandler](clazz: Class[H]): F[H] =
      pipeline.removeF(clazz)

    def removeHandler(name: String): F[ChannelHandler] =
      pipeline.removeF(name)

    def removeHandler(handler: ChannelHandler): F[Unit] =
      pipeline.removeF(handler)

    def addLast(handlers: ChannelHandler*): F[Unit] =
      pipeline.addLastF(handlers: _*)

    def addLast(name: String, handler: ChannelHandler): F[Unit] =
      pipeline.addLastF(name, handler)

    def getContext(channelHandler: ChannelHandler): F[ChannelHandlerContext] =
      pipeline.contextF(channelHandler)

    def writeAndFlushF(x: AnyRef): F[ChannelFuture] = F.delay(ctx.writeAndFlush(x))

    def channelWriteAndFlush(x: AnyRef): F[ChannelFuture] = F.delay(ctx.channel().writeAndFlush(x))

    def closeF: F[ChannelFuture] = F.delay(ctx.close())

    def addLast[I](
      handlerName: String,
      handler: ChannelHandlerF[F, I]
    ): F[NettyToCatsEffectRuntimeHandler[F, I]] =
      ChannelHandlerF.asNetty(handler).flatTap(h => addLast(handlerName, h))

    private def pipeline = ctx.pipeline()
  }

  implicit class ChannelOps[F[_]](channel: Channel)(implicit F: Sync[F]) {
    def setAutoRead(autoRead: Boolean): F[Unit] =
      F.delay(channel.config().setAutoRead(autoRead)).void

    def writeAndFlushF(x: AnyRef): F[ChannelFuture] = F.delay(channel.writeAndFlush(x))

  }

  implicit class PipelineOps[F[_]](pipeline: ChannelPipeline)(implicit F: Async[F]) {

    def addLastF(handlers: ChannelHandler*): F[Unit] =
      F.delay(pipeline.addLast(handlers: _*)).void

    def addLastF(name: String, handler: ChannelHandler): F[Unit] =
      F.delay(pipeline.addLast(name, handler)).void

    def addLast(
      handlerF: ChannelHandlerF[F, _]
    ): F[Unit] = ChannelHandlerF.asNetty(handlerF).flatMap(addLastF(_))

    def removeF[H <: ChannelHandler](clazz: Class[H]): F[H] =
      F.delay(pipeline.remove(clazz))

    def removeF(name: String): F[ChannelHandler] =
      F.delay(pipeline.remove(name))

    def removeF(handler: ChannelHandler): F[Unit] =
      F.delay(pipeline.remove(handler)).void

    def contextF(channelHandler: ChannelHandler): F[ChannelHandlerContext] =
      F.delay(pipeline.context(channelHandler))
  }
}
