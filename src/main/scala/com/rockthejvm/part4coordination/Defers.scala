package com.rockthejvm.part4coordination

import cats.effect.kernel.{Fiber, Outcome}
import cats.effect.{Deferred, IO, IOApp, Ref}
import com.rockthejvm.utils.*
import cats.syntax.traverse.*

import scala.concurrent.duration.*

object Defers extends IOApp.Simple {

  // deferred is a primitive for waiting for an effect, while some other effect completes with a value
  val aDeferred: IO[Deferred[IO, Int]] = Deferred[IO, Int]
  val aDeferred_v2: IO[Deferred[IO, Int]] = IO.deferred[Int] // same

  // get blocks the calling fiber (semantically) until some other fiber completes the Deferred with a value
  val reader: IO[Int] = aDeferred.flatMap { signal =>
    signal.get // blocks the fiber
  }

  val writer = aDeferred.flatMap { signal =>
    signal.complete(42)
  }

  def demoDeferred(): IO[Unit] = {
    def consumer(signal: Deferred[IO, Int]) = for {
      _ <- IO("[consumer] waiting for the result...").debugging
      meaningOfLife <- signal.get // blocker
      _ <- IO(s"[consumer] got the result: $meaningOfLife").debugging
    } yield ()

    def producer(signal: Deferred[IO, Int]) = for {
      _ <- IO("[producer] crunching numbers...").debugging
      _ <- IO.sleep(1.second)
      _ <- IO("[producer] complete: 42").debugging
      meaningOfLife <- IO(42)
      _ <- signal.complete(meaningOfLife)
    } yield ()

    for {
      signal <- Deferred[IO, Int]
      fibConsumer <- consumer(signal).start
      fibProducer <- producer(signal).start
      _ <- fibProducer.join
      _ <- fibConsumer.join
    } yield ()
  }

  // simulate downloading some content
  val fileParts = List("I ", "love S", "cala", " with Cat", "s Effect!<EOF>")

  def fileNotifierWithRef(): IO[Unit] = {
    def downloadFile(contentRef: Ref[IO, String]): IO[Unit] =
      fileParts
        .map { part =>
          IO(s"[downloader] got '$part'").debugging >> IO.sleep(
            1.second
          ) >> contentRef
            .update(_ + part)
        }
        .sequence
        .void

    def notifyFileComplete(contentRef: Ref[IO, String]): IO[Unit] = for {
      file <- contentRef.get
      _ <-
        if (file.endsWith("<EOF>"))
          IO("[notifier] File download complete").debugging
        else
          IO("[notifier] downloading...").debugging >> IO.sleep( // busy wait
            500.millis
          ) >> notifyFileComplete(
            contentRef
          )
    } yield ()

    for {
      contentRef <- Ref[IO].of("")
      fibDownloader <- downloadFile(contentRef).start
      notifier <- notifyFileComplete(contentRef).start
      _ <- fibDownloader.join
      _ <- notifier.join
    } yield ()
  }

  // deferred works miracles for waiting
  def fileNotifierWithDeferred(): IO[Unit] = {
    def notifyFileComplete(signal: Deferred[IO, String]): IO[Unit] = for {
      _ <- IO("[notifier] downloading...").debugging
      _ <- signal.get // blocks until the signal is completed
      _ <- IO("[notifier] File download complete").debugging
    } yield ()

    def downloadFilePart(
        part: String,
        contentRef: Ref[IO, String],
        signal: Deferred[IO, String]
    ): IO[Unit] = for {
      _ <- IO(s"[downloader] got '$part'").debugging
      _ <- IO.sleep(1.second)
      latestContent <- contentRef.updateAndGet(_ + part)
      _ <-
        if (latestContent.contains("<EOF>")) signal.complete(latestContent)
        else IO.unit
    } yield ()

    for {
      contentRef <- Ref[IO].of("")
      signal <- Deferred[IO, String]
      notifierFib <- notifyFileComplete(signal).start
      fileTasksFib <- fileParts
        .map(part => downloadFilePart(part, contentRef, signal))
        .sequence
        .start
      _ <- notifierFib.join
      _ <- fileTasksFib.join
    } yield ()
  }

  /** Exercises:
    *   - (medium) write a small alarm notification with two simultaneous IOs
    *     - one that increments a counter every second (a clock)
    *     - one that waits for the counter to become 10, then prints a message
    *       "time's up"
    *   - (mega hard) implement racePair with Deferred.
    *     - use a Deferred which can hold an Either[outcome for ioa, outcome for
    *       iob]
    *     - start two fibers, one for each IO
    *     - on completion (with any status), each IO needs to complete that
    *       Deferred (hint: use a finalizer from the Resources lesson) (hint2:
    *       use a guarantee call to make sure the fibers complete the Deferred)
    *     - what do you do in case of cancellation (the hardest part?)
    */

  def eggBoiler(): IO[Unit] = {
    def tickingClock(
        counter: Ref[IO, Int],
        signal: Deferred[IO, Unit]
    ): IO[Unit] = for {
      _ <- IO.sleep(1.second)
      count <- counter.updateAndGet(_ + 1)
      _ <- IO(s"[incrementer] updated counter: $count").debugging
      _ <-
        if (count == 10) signal.complete(IO.unit)
        else tickingClock(counter, signal)
    } yield ()

    def eggReadyNotification(signal: Deferred[IO, Unit]) = for {
      _ <- IO("Egg boiling on some other fiber, waiting...").debugging
      _ <- signal.get
      _ <- IO("EGG READY!").debugging
    } yield ()

    for {
      counter <- Ref[IO].of(0)
      signal <- Deferred[IO, Unit]
      fibNotifier <- eggReadyNotification(signal).start
      clock <- tickingClock(counter, signal).start
      _ <- fibNotifier.join
      _ <- clock.join
    } yield ()
  }

  type RaceResult[A, B] = Either[
    (Outcome[IO, Throwable, A], Fiber[IO, Throwable, B]),
    (Fiber[IO, Throwable, A], Outcome[IO, Throwable, B])
  ]

  type EitherOutcome[A, B] =
    Either[Outcome[IO, Throwable, A], Outcome[IO, Throwable, B]]

  def ourRacePair[A, B](ioa: IO[A], iob: IO[B]): IO[RaceResult[A, B]] =
    IO.uncancelable { poll =>
      for {
        signal <- Deferred[IO, EitherOutcome[A, B]]
        fibA <- ioa
          .guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void)
          .start
        fibB <- iob
          .guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void)
          .start
        result <- poll(signal.get)
          .onCancel { // blocking call - should be cancellable
            for {
              cancelFibA <- fibA.cancel.start
              cancelFibB <- fibB.cancel.start
              _ <- cancelFibA.join
              _ <- cancelFibB.join
            } yield ()
          }
      } yield result match
        case Left(outcomeA)  => Left(outcomeA, fibB)
        case Right(outcomeB) => Right(fibA, outcomeB)
    }

  override def run: IO[Unit] = eggBoiler()

}
