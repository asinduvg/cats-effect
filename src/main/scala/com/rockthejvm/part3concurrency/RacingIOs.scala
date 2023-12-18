package com.rockthejvm.part3concurrency

import cats.effect.kernel.Outcome
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded, errored}
import cats.effect.{Fiber, IO, IOApp}

import scala.concurrent.duration.*

object RacingIOs extends IOApp.Simple {

  import com.rockthejvm.utils._
  def runWithSleep[A](value: A, duration: FiniteDuration): IO[A] =
    (IO(s"starting computation: $value").debugging >>
      IO.sleep(duration) >>
      IO(s"computation for $value: done") >>
      IO(value))
      .onCancel(IO(s"computation CANCELLED for $value").debugging.void)

  def testRace() = {
    val meaningOfLife = runWithSleep(42, 1.second)
    val favLang = runWithSleep("Scala", 2.seconds)
    val first: IO[Either[Int, String]] = IO.race(meaningOfLife, favLang)
    /*
      - both IOs run on separate fibers
      - the first one to finish will complete the result
      - the loser will be cancelled
     */

    first.flatMap {
      case Left(mol)   => IO(s"Meaning of life won: $mol")
      case Right(lang) => IO(s"Fav language won: $lang")
    }
  }

  def testRacePair() = {
    val meaningOfLife = runWithSleep(42, 1.second)
    val favLang = runWithSleep("Scala", 2.seconds)
    val first: IO[Either[
      (
          Outcome[IO, Throwable, Int],
          Fiber[IO, Throwable, String]
      ), // (winner result, loser fiber)
      (
          Fiber[IO, Throwable, Int],
          Outcome[IO, Throwable, String]
      ) // (loser fiber, winner result)
    ]] = IO.racePair(meaningOfLife, favLang)

    first.flatMap {
      case Left(outMol, fibLang) =>
        fibLang.cancel >> IO("MOL won").debugging >> IO(outMol).debugging
      case Right(fibMol, outLang) =>
        fibMol.cancel >> IO("Language won").debugging >> IO(outLang).debugging
    }
  }

  /** Exercises: 1 - implement a timeout pattern with race 2 - a method to
    * return a LOSING effect from a race (hint: use racePair) 3 - implement race
    * in terms of racePair
    */
  // 1
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] =
    val timeout = IO.sleep(duration)
    val first = IO.race(io, timeout)

    first.flatMap {
      case Left(a) => IO(a)
      case Right(_) =>
        IO.raiseError(new RuntimeException("Computation timed out"))
    }

  val importantTask = IO.sleep(2.seconds) >> IO(42).debugging
  val testTimeout = timeout(importantTask, 1.second)
  val testTimeout_v2 = importantTask.timeout(1.second)

  // 2
  def unrace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    val result = IO.racePair(ioa, iob)
    result.flatMap {
      case Left((_, bLose)) =>
        bLose.join.flatMap { // join to hold the winner
          case Succeeded(resultEffect) =>
            resultEffect.map(result => Right(result))
          case Errored(e) => IO.raiseError(e)
          case Canceled() =>
            IO.raiseError(new RuntimeException("Loser cancelled"))
        }
      case Right((aLose, _)) =>
        aLose.join.flatMap {
          case Succeeded(resultEffect) =>
            resultEffect.map(result => Left(result))
          case Errored(e) => IO.raiseError(e)
          case Canceled() =>
            IO.raiseError(new RuntimeException("Loser cancelled"))
        }
    }

  // 3
  def simpleRace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
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

  override def run: IO[Unit] =
    testRace().debugging.void

}
