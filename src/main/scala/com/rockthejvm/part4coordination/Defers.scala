package com.rockthejvm.part4coordination

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

  override def run: IO[Unit] = fileNotifierWithDeferred()

}
