package com.rockthejvm.part4coordination

import cats.effect.kernel.Deferred
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.{Concurrent, IO, IOApp, Ref}

import scala.util.Random
import scala.concurrent.duration.*
import com.rockthejvm.utils.*
import cats.syntax.parallel.*

import scala.collection.immutable.Queue

// generic mutex after the polymorphic concurrent exercise

abstract class MutexV2[F[_]] {
  def acquire: F[Unit]
  def release: F[Unit]
}

object MutexV2 {

  import cats.syntax.functor.*
  import cats.syntax.flatMap.*
  import cats.effect.syntax.monadCancel.*

  type Signal[F[_]] = Deferred[F, Unit]

  case class State[F[_]](locked: Boolean, waiting: Queue[Signal[F]])

  def unlocked[F[_]] = State[F](locked = false, waiting = Queue())

  def createSignal[F[_]](using concurrent: Concurrent[F]): F[Signal[F]] =
    concurrent.deferred[Unit]

  def create[F[_]](using concurrent: Concurrent[F]): F[MutexV2[F]] =
    concurrent.ref(unlocked).map { createMutexWithCancellation }

  def createMutexWithCancellation[F[_]](state: Ref[F, State[F]])(using
      concurrent: Concurrent[F]
  ): MutexV2[F] =
    new MutexV2[F]:
      override def acquire: F[Unit] =
        concurrent.uncancelable { poll =>
          createSignal.flatMap { signal =>

            val cleanup = state.modify { case State(locked, queue) =>
              val newQueue = queue.filterNot(_ eq signal)
              val isBlocking = queue.exists(_ eq signal)
              val decision = if (isBlocking) concurrent.unit else release
              State(locked, newQueue) -> decision
            }.flatten

            state.modify {
              case State(false, _) => State[F](true, Queue()) -> concurrent.unit
              case State(true, queue) =>
                State[F](true, queue.enqueue(signal)) -> poll(signal.get)
                  .onCancel(
                    cleanup
                  )
            }.flatten
          }
        }
      override def release: F[Unit] = state.modify {
        case State(false, _) => unlocked[F] -> concurrent.unit
        case State(true, queue) =>
          if (queue.isEmpty) unlocked[F] -> concurrent.unit
          else {
            val (signal, rest) = queue.dequeue
            State[F](true, rest) -> signal.complete(()).void
          }
      }.flatten

}

abstract class Mutex {
  def acquire: IO[Unit]
  def release: IO[Unit]
}

object Mutex {
  type Signal = Deferred[IO, Unit]
  case class State(locked: Boolean, waiting: Queue[Signal])
  val unlocked = State(locked = false, waiting = Queue())

  def createSignal(): IO[Signal] = Deferred[IO, Unit]

  def create: IO[Mutex] =
    Ref[IO].of(unlocked).map { createMutexWithCancellation }

  def createMutexWithCancellation(state: Ref[IO, State]): Mutex =
    new Mutex:
      /*
        Change the state of the Ref:
          - if the mutex is currently unlocked, state becomes (true, [])
          - if the mutex is locked, state becomes (true, queue + new signal) AND WAIT ON THAT SIGNAL.
       */
      override def acquire: IO[Unit] = IO.uncancelable { poll =>
        createSignal().flatMap { signal =>

          val cleanup = state.modify { case State(locked, queue) =>
            val newQueue = queue.filterNot(_ eq signal)
            val isBlocking = queue.exists(_ eq signal)
            val decision = if (isBlocking) IO.unit else release
            State(locked, newQueue) -> decision
          }.flatten

          state.modify { // IO[IO[Unit]]
            case State(false, _) => State(true, Queue()) -> IO.unit
            case State(true, queue) =>
              State(true, queue.enqueue(signal)) -> poll(signal.get).onCancel(
                cleanup
              )
          }.flatten // modify returns IO[B], our B is IO[Unit], so modify returns IO[IO[Unit]], we need to flatten
        }
      }

      /*
        Change the state of the Ref:
          - if the mutex is unlocked, leave the state unchanged
          - if the mutex is locked,
            - if the queue is empty, unlock the mutex, i.e state becomes (false, [])
            - if the queue is not empty, take a signal out of the queue and complete it (thereby unblocking a fiber waiting on it)
       */
      override def release: IO[Unit] = state.modify {
        case State(false, _) => unlocked -> IO.unit
        case State(true, queue) =>
          if (queue.isEmpty) unlocked -> IO.unit
          else {
            val (signal, rest) = queue.dequeue
            State(true, rest) -> signal.complete(IO.unit).void
          }
      }.flatten

  def createSimpleMutex(state: Ref[IO, State]): Mutex =
    new Mutex:
      /*
        Change the state of the Ref:
          - if the mutex is currently unlocked, state becomes (true, [])
          - if the mutex is locked, state becomes (true, queue + new signal) AND WAIT ON THAT SIGNAL.
       */
      override def acquire: IO[Unit] = createSignal().flatMap { signal =>
        state.modify { // IO[IO[Unit]]
          case State(false, _) => State(true, Queue()) -> IO.unit
          case State(true, queue) =>
            State(true, queue.enqueue(signal)) -> signal.get
        }.flatten // modify returns IO[B], our B is IO[Unit], so modify returns IO[IO[Unit]], we need to flatten
      }

      /*
        Change the state of the Ref:
          - if the mutex is unlocked, leave the state unchanged
          - if the mutex is locked,
            - if the queue is empty, unlock the mutex, i.e state becomes (false, [])
            - if the queue is not empty, take a signal out of the queue and complete it (thereby unblocking a fiber waiting on it)
       */
      override def release: IO[Unit] = state.modify {
        case State(false, _) => unlocked -> IO.unit
        case State(true, queue) =>
          if (queue.isEmpty) unlocked -> IO.unit
          else {
            val (signal, rest) = queue.dequeue
            State(true, rest) -> signal.complete(IO.unit).void
          }
      }.flatten

}

object MutexPlayground extends IOApp.Simple {
  def criticalTask(): IO[Int] = IO.sleep(1.second) >> IO(Random.nextInt(100))

  def createNonLockingTask(id: Int): IO[Int] = for {
    _ <- IO(s"[task $id] working...").debugging
    res <- criticalTask()
    _ <- IO(s"[task $id] got result: $res").debugging
  } yield res

  def demoNonLockingTasks(): IO[List[Int]] =
    (1 to 10).toList.parTraverse(createNonLockingTask)

  def createLockingTask(id: Int, mutex: MutexV2[IO]): IO[Int] = for {
    _ <- IO(s"[task $id] waiting for permission...").debugging
    _ <-
      mutex.acquire // blocks if the mutex has been acquired by some other fiber
      // critical section
    _ <- IO(s"[task $id] working...").debugging
    res <- criticalTask()
    _ <- IO(s"[task $id] got result: $res").debugging
    // critical section end
    _ <- mutex.release
    _ <- IO(s"[task $id] lock removed.").debugging
  } yield res

  def demoLockingTasks() = for {
    mutex <- MutexV2.create[IO]
    result <- (1 to 10).toList.parTraverse(id => createLockingTask(id, mutex))
  } yield result
  // only one task will proceed at one time

  def createCancellingTask(id: Int, mutex: MutexV2[IO]): IO[Int] =
    if (id % 2 == 0) createLockingTask(id, mutex)
    else
      for {
        fib <- createLockingTask(id, mutex)
          .onCancel(IO(s"[task $id] received cancellation!").debugging.void)
          .start
        _ <- IO.sleep(2.seconds) >> fib.cancel
        out <- fib.join
        result <- out match
          case Succeeded(effect) => effect
          case Errored(_)        => IO(-1)
          case Canceled()        => IO(-2)
      } yield result

  def demoCancellingTasks() = for {
    mutex <- MutexV2.create[IO]
    result <- (1 to 10).toList.parTraverse(id =>
      createCancellingTask(id, mutex)
    )
  } yield result

  override def run: IO[Unit] = demoCancellingTasks().debugging.void

}
