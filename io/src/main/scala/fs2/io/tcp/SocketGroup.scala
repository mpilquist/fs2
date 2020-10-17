/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package tcp

import scala.concurrent.duration._

import java.net.{InetSocketAddress, SocketAddress, StandardSocketOptions}
import java.nio.{Buffer, ByteBuffer}
import java.nio.channels.{
  AsynchronousCloseException,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.{ThreadFactory, TimeUnit}

import cats.Applicative
import cats.syntax.all._
import cats.effect.{Resource, Sync}
import cats.effect.kernel.Ref
import cats.effect.std.Semaphore

import fs2.internal.ThreadFactories

/** Resource that provides the ability to open client and server TCP sockets that all share
  * an underlying non-blocking channel group.
  */
final class SocketGroup(channelGroup: AsynchronousChannelGroup) {

  /** Opens a connection to the specified server represented as a [[Socket]].
    * The connection is closed when the resource is released.
    *
    * @param to                   address of remote server
    * @param reuseAddress         whether address may be reused (see `java.net.StandardSocketOptions.SO_REUSEADDR`)
    * @param sendBufferSize       size of send buffer  (see `java.net.StandardSocketOptions.SO_SNDBUF`)
    * @param receiveBufferSize    size of receive buffer  (see `java.net.StandardSocketOptions.SO_RCVBUF`)
    * @param keepAlive            whether keep-alive on tcp is used (see `java.net.StandardSocketOptions.SO_KEEPALIVE`)
    * @param noDelay              whether tcp no-delay flag is set  (see `java.net.StandardSocketOptions.TCP_NODELAY`)
    */
  def client[F[_]](
      to: InetSocketAddress,
      reuseAddress: Boolean = true,
      sendBufferSize: Int = 256 * 1024,
      receiveBufferSize: Int = 256 * 1024,
      keepAlive: Boolean = false,
      noDelay: Boolean = false,
      additionalSocketOptions: List[SocketOptionMapping[_]] = List.empty
  )(implicit F: Network[F]): Resource[F, Socket[F]] = {
    import F.async
    def setup: F[AsynchronousSocketChannel] =
      F.async.blocking {
        val ch =
          AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(channelGroup)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
        additionalSocketOptions.foreach { case SocketOptionMapping(option, value) =>
          ch.setOption(option, value)
        }
        ch
      }

    def connect(ch: AsynchronousSocketChannel): F[AsynchronousSocketChannel] =
      F.async.async_[AsynchronousSocketChannel] { cb =>
        ch.connect(
          to,
          null,
          new CompletionHandler[Void, Void] {
            def completed(result: Void, attachment: Void): Unit =
              cb(Right(ch))
            def failed(rsn: Throwable, attachment: Void): Unit =
              cb(Left(rsn))
          }
        )
      }

    Resource.liftF(setup.flatMap(connect)).flatMap(apply(_))
  }

  /** Stream that binds to the specified address and provides a connection for,
    * represented as a [[Socket]], for each client that connects to the bound address.
    *
    * Returns a stream of stream of sockets.
    *
    * The outer stream scopes the lifetime of the server socket.
    * When the outer stream terminates, all open connections will terminate as well.
    * The outer stream emits an element (an inner stream) for each client connection.
    *
    * Each inner stream represents an individual connection, and as such, is a stream
    * that emits a single socket. Failures that occur in an inner stream do *NOT* cause
    * the outer stream to fail.
    *
    * @param address            address to accept connections from
    * @param maxQueued          number of queued requests before they will become rejected by server
    *                           (supply <= 0 for unbounded)
    * @param reuseAddress       whether address may be reused (see `java.net.StandardSocketOptions.SO_REUSEADDR`)
    * @param receiveBufferSize  size of receive buffer (see `java.net.StandardSocketOptions.SO_RCVBUF`)
    */
  def server[F[_]: Applicative](
      address: InetSocketAddress,
      reuseAddress: Boolean = true,
      receiveBufferSize: Int = 256 * 1024,
      additionalSocketOptions: List[SocketOptionMapping[_]] = List.empty
  )(implicit F: Network[F]): Stream[F, Resource[F, Socket[F]]] =
    Stream
      .resource(
        serverResource(
          address,
          reuseAddress,
          receiveBufferSize,
          additionalSocketOptions
        )
      )
      .flatMap { case (_, clients) => clients }

  /** Like [[server]] but provides the `InetSocketAddress` of the bound server socket before providing accepted sockets.
    * The inner stream emits one socket for each client that connects to the server.
    */
  def serverResource[F[_]](
      address: InetSocketAddress,
      reuseAddress: Boolean = true,
      receiveBufferSize: Int = 256 * 1024,
      additionalSocketOptions: List[SocketOptionMapping[_]] = List.empty
  )(implicit F: Network[F]): Resource[F, (InetSocketAddress, Stream[F, Resource[F, Socket[F]]])] = {
    import F.async

    val setup: F[AsynchronousServerSocketChannel] = F.async.blocking {
      val ch = AsynchronousChannelProvider
        .provider()
        .openAsynchronousServerSocketChannel(channelGroup)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
      additionalSocketOptions.foreach { case SocketOptionMapping(option, value) =>
        ch.setOption(option, value)
      }
      ch.bind(address)
      ch
    }

    def cleanup(sch: AsynchronousServerSocketChannel): F[Unit] =
      F.async.blocking(if (sch.isOpen) sch.close())

    def acceptIncoming(sch: AsynchronousServerSocketChannel): Stream[F, Resource[F, Socket[F]]] = {
      def go: Stream[F, Resource[F, Socket[F]]] = {
        def acceptChannel: F[AsynchronousSocketChannel] =
          F.async.async_[AsynchronousSocketChannel] { cb =>
            sch.accept(
              null,
              new CompletionHandler[AsynchronousSocketChannel, Void] {
                def completed(ch: AsynchronousSocketChannel, attachment: Void): Unit =
                  cb(Right(ch))
                def failed(rsn: Throwable, attachment: Void): Unit =
                  cb(Left(rsn))
              }
            )
          }

        Stream.eval(acceptChannel.attempt).flatMap {
          case Left(_)         => Stream.empty[F]
          case Right(accepted) => Stream.emit(apply(accepted))
        } ++ go
      }

      go.handleErrorWith {
        case err: AsynchronousCloseException =>
          Stream.eval(F.async.blocking(sch.isOpen)).flatMap { isOpen =>
            if (isOpen) Stream.raiseError[F](err)
            else Stream.empty
          }
        case err => Stream.raiseError[F](err)
      }
    }

    Resource.make(setup)(cleanup).map { sch =>
      val localAddress = sch.getLocalAddress.asInstanceOf[InetSocketAddress]
      (localAddress, acceptIncoming(sch))
    }
  }

  private def apply[F[_]](
      ch: AsynchronousSocketChannel
  )(implicit F: Network[F]): Resource[F, Socket[F]] = {
    import F.async
    val socket = (Semaphore[F](1), Semaphore[F](1), Ref[F].of(ByteBuffer.allocate(0))).mapN {
      (readSemaphore, writeSemaphore, bufferRef) =>
        // Reads data to remaining capacity of supplied ByteBuffer
        // Also measures time the read took returning this as tuple
        // of (bytes_read, read_duration)
        def readChunk(buff: ByteBuffer, timeoutMs: Long): F[(Int, Long)] =
          F.async.async_[(Int, Long)] { cb =>
            val started = System.currentTimeMillis()
            ch.read(
              buff,
              timeoutMs,
              TimeUnit.MILLISECONDS,
              (),
              new CompletionHandler[Integer, Unit] {
                def completed(result: Integer, attachment: Unit): Unit = {
                  val took = System.currentTimeMillis() - started
                  cb(Right((result, took)))
                }
                def failed(err: Throwable, attachment: Unit): Unit =
                  cb(Left(err))
              }
            )
          }

        // gets buffer of desired capacity, ready for the first read operation
        // If the buffer does not have desired capacity it is resized (recreated)
        // buffer is also reset to be ready to be written into.
        def getBufferOf(sz: Int): F[ByteBuffer] =
          bufferRef.get.flatMap { buff =>
            if (buff.capacity() < sz)
              F.async.delay(ByteBuffer.allocate(sz)).flatTap(bufferRef.set)
            else
              F.async.delay {
                (buff: Buffer).clear()
                (buff: Buffer).limit(sz)
                buff
              }
          }

        // When the read operation is done, this will read up to buffer's position bytes from the buffer
        // this expects the buffer's position to be at bytes read + 1
        def releaseBuffer(buff: ByteBuffer): F[Chunk[Byte]] =
          F.async.delay {
            val read = buff.position()
            val result =
              if (read == 0) Chunk.bytes(Array.empty)
              else {
                val dest = new Array[Byte](read)
                (buff: Buffer).flip()
                buff.get(dest)
                Chunk.bytes(dest)
              }
            (buff: Buffer).clear()
            result
          }

        def read0(max: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
          readSemaphore.withPermit {
            getBufferOf(max).flatMap { buff =>
              readChunk(buff, timeout.map(_.toMillis).getOrElse(0L)).flatMap { case (read, _) =>
                if (read < 0) F.async.pure(None)
                else releaseBuffer(buff).map(Some(_))
              }
            }
          }

        def readN0(max: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
          readSemaphore.withPermit {
            getBufferOf(max).flatMap { buff =>
              def go(timeoutMs: Long): F[Option[Chunk[Byte]]] =
                readChunk(buff, timeoutMs).flatMap { case (readBytes, took) =>
                  if (readBytes < 0 || buff.position() >= max)
                    // read is done
                    releaseBuffer(buff).map(Some(_))
                  else go((timeoutMs - took).max(0))
                }

              go(timeout.map(_.toMillis).getOrElse(0L))
            }
          }

        def write0(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] = {
          def go(buff: ByteBuffer, remains: Long): F[Unit] =
            F.async
              .async_[Option[Long]] { cb =>
                val start = System.currentTimeMillis()
                ch.write(
                  buff,
                  remains,
                  TimeUnit.MILLISECONDS,
                  (),
                  new CompletionHandler[Integer, Unit] {
                    def completed(result: Integer, attachment: Unit): Unit =
                      cb(
                        Right(
                          if (buff.remaining() <= 0) None
                          else Some(System.currentTimeMillis() - start)
                        )
                      )
                    def failed(err: Throwable, attachment: Unit): Unit =
                      cb(Left(err))
                  }
                )
              }
              .flatMap {
                case None       => F.async.unit
                case Some(took) => go(buff, (remains - took).max(0))
              }
          writeSemaphore.withPermit {
            go(bytes.toByteBuffer, timeout.map(_.toMillis).getOrElse(0L))
          }
        }

        ///////////////////////////////////
        ///////////////////////////////////

        new Socket[F] {
          def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
            readN0(numBytes, timeout)
          def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
            read0(maxBytes, timeout)
          def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] =
            Stream.eval(read(maxBytes, timeout)).flatMap {
              case Some(bytes) =>
                Stream.chunk(bytes) ++ reads(maxBytes, timeout)
              case None => Stream.empty
            }

          def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
            write0(bytes, timeout)
          def writes(timeout: Option[FiniteDuration]): Pipe[F, Byte, INothing] =
            _.chunks.foreach(write(_, timeout))

          def localAddress: F[SocketAddress] =
            F.async.blocking(ch.getLocalAddress)
          def remoteAddress: F[SocketAddress] =
            F.async.blocking(ch.getRemoteAddress)
          def isOpen: F[Boolean] = F.async.blocking(ch.isOpen)
          def close: F[Unit] = F.async.blocking(ch.close())
          def endOfOutput: F[Unit] =
            F.async.blocking {
              ch.shutdownOutput(); ()
            }
          def endOfInput: F[Unit] =
            F.async.blocking {
              ch.shutdownInput(); ()
            }
        }
    }
    Resource.make(socket)(_ => F.async.blocking(if (ch.isOpen) ch.close else ()).attempt.void)
  }
}

object SocketGroup {

  /** Creates a `SocketGroup`.
    *
    * The supplied `blocker` is used for networking calls other than
    * reads/writes. All reads and writes are performed on a non-blocking thread pool
    * associated with the `SocketGroup`. The non-blocking thread pool is sized to
    * the number of available processors but that can be overridden by supplying
    * a value for `nonBlockingThreadCount`. See
    * https://openjdk.java.net/projects/nio/resources/AsynchronousIo.html for more
    * information on NIO thread pooling.
    */
  def apply[F[_]: Sync](
      nonBlockingThreadCount: Int = 0,
      nonBlockingThreadFactory: ThreadFactory =
        ThreadFactories.named("fs2-socket-group-non-blocking", true)
  ): Resource[F, SocketGroup] =
    Resource(Sync[F].blocking {
      val threadCount =
        if (nonBlockingThreadCount <= 0) Runtime.getRuntime.availableProcessors
        else nonBlockingThreadCount
      val acg = AsynchronousChannelGroup.withFixedThreadPool(threadCount, nonBlockingThreadFactory)
      val group = new SocketGroup(acg)
      (group, Sync[F].blocking(acg.shutdown()))
    })
}
