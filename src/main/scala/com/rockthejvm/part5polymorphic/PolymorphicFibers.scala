package com.rockthejvm.part5polymorphic

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.{Fiber, IO, IOApp, MonadCancel, Outcome, Spawn}

object PolymorphicFibers extends IOApp.Simple {

  trait MyGenSpawn[F[_], E] extends MonadCancel[F, E] {
    def start[A](fa: F[A]): F[Fiber[F, Throwable, A]] // create a fiber
    def never[A]: F[A] // a forever-suspending effect
    def cede: F[Unit] // a "yield" effect

    def raisePair[A, B](fa: F[A], fb: F[B]): F[Either[ // fundamental racing
      (Outcome[F, E, A], Fiber[F, E, B]),
      (Fiber[F, E, A], Outcome[F, E, B])
    ]]
  }

  // Spawn = create fibers for any effect
  trait MySpawn[F[_]] extends MyGenSpawn[F, Throwable] {
    def start[A](fa: F[A]): F[Fiber[F, Throwable, A]] // create a fiber
    def never[A]: F[A] // a forever-suspending effect
    def cede: F[Unit] // a "yield" effect
  }
  val mol = IO(42)
  val fiber: IO[Fiber[IO, Throwable, Int]] = mol.start

  // pure, map/flatMap, raiseError, uncanceleble, start

  val spawnIO = Spawn[IO] // fetch the given/implicit Spawn[IO]

  def ioOnSomeThread[A](io: IO[A]): IO[Outcome[IO, Throwable, A]] = for {
    fib <- spawnIO.start(io) // io.start assumes the presence of a Spawn[IO]
    result <- fib.join
  } yield result

  import cats.syntax.functor.* // map
  import cats.syntax.flatMap.* // flatMap

  // generalize
  import cats.effect.syntax.spawn.* // start extension methods
  def effectOnSomeThread[F[_], A](
      fa: F[A]
  )(using spawn: Spawn[F]): F[Outcome[F, Throwable, A]] = for {
    fib <- fa.start
    result <- fib.join
  } yield result

  val molOnFiber = ioOnSomeThread(mol)
  val molOnFiber_v2 = effectOnSomeThread(mol)

  /** Exercise - generalize the following code
    */

  def ioRace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob).flatMap {
      case Left((outA, fibB)) =>
        outA match
          case Succeeded(effectA) => fibB.cancel >> effectA.map(a => Left(a))
          case Errored(e)         => fibB.cancel >> IO.raiseError(e)
          case Canceled() =>
            fibB.join.flatMap {
              case Succeeded(effectB) => effectB.map(b => Right(b))
              case Errored(e)         => IO.raiseError(e)
              case Canceled() =>
                IO.raiseError(
                  new RuntimeException("Both computations cancelled.")
                )
            }
      case Right((fibA, outB)) =>
        outB match
          case Succeeded(effectB) => fibA.cancel >> effectB.map(b => Right(b))
          case Errored(e)         => fibA.cancel >> IO.raiseError(e)
          case Canceled() =>
            fibA.join.flatMap {
              case Succeeded(effectA) => effectA.map(a => Left(a))
              case Errored(e)         => IO.raiseError(e)
              case Canceled() =>
                IO.raiseError(
                  new RuntimeException("Both computations cancelled.")
                )
            }
    }

  def generalRace[A, B, F[_]](fa: F[A], fb: F[B])(using
      spawn: Spawn[F]
  ): F[Either[A, B]] =
    spawn.racePair(fa, fb).flatMap {
      case Left((outA, fibB)) =>
        outA match
          case Succeeded(effectA) => fibB.cancel >> effectA.map(a => Left(a))
          case Errored(e)         => fibB.cancel >> spawn.raiseError(e)
          case Canceled() =>
            fibB.join.flatMap {
              case Succeeded(effectB) => effectB.map(b => Right(b))
              case Errored(e)         => spawn.raiseError(e)
              case Canceled() =>
                spawn.raiseError(
                  new RuntimeException("Both computations cancelled.")
                )
            }
      case Right((fibA, outB)) =>
        outB match
          case Succeeded(effectB) => fibA.cancel >> effectB.map(b => Right(b))
          case Errored(e)         => fibA.cancel >> spawn.raiseError(e)
          case Canceled() =>
            fibA.join.flatMap {
              case Succeeded(effectA) => effectA.map(a => Left(a))
              case Errored(e)         => spawn.raiseError(e)
              case Canceled() =>
                spawn.raiseError(
                  new RuntimeException("Both computations cancelled.")
                )
            }
    }

  import scala.concurrent.duration.*
  import com.rockthejvm.utils.general.*

  val fast = IO.sleep(1.seconds) >> IO(42).debugging
  val slow = IO.sleep(2.seconds) >> IO("Scala").debugging
  val race = ioRace(fast, slow)
  val race_v2 = generalRace(fast, slow)

  override def run: IO[Unit] = race_v2.void
}
