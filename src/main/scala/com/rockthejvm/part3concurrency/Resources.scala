package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}

import java.io.{File, FileReader}
import java.util.Scanner
import scala.concurrent.duration.*

object Resources extends IOApp.Simple {

  import com.rockthejvm.utils._

  // use-case: manage a connection lifecycle
  class Connection(url: String) {
    def open(): IO[String] = IO(s"Opening connection to $url").debugging
    def close(): IO[String] = IO(s"Closing connection to $url").debugging
  }

  val asyncFetchUrl = for {
    fib <- (new Connection("rockthejvm.com").open() *> IO.sleep(
      Int.MaxValue.seconds
    )).start
    _ <- IO.sleep(1.second) *> fib.cancel
  } yield ()
  // problem: leaking resources

  val correctAsyncFetchUrl = for {
    conn <- IO(new Connection("rockthejvm.com"))
    fib <- (conn.open() *> IO.sleep(Int.MaxValue.seconds))
      .onCancel(conn.close().void)
      .start
    _ <- IO.sleep(1.second) *> fib.cancel
  } yield ()

  /*
    bracket pattern: someIO.bracket(useResourceCb)(releaseResourceCb)
    bracket is equivalent to try-catches (pure FP)
   */
  val bracketFetchUrl = IO(new Connection("rockthejvm.com"))
    .bracket(conn => conn.open() *> IO.sleep(Int.MaxValue.seconds))(conn =>
      conn.close().void
    )

  val bracketProgram = for {
    fib <- bracketFetchUrl.start
    _ <- IO.sleep(1.second) *> fib.cancel
  } yield ()

  /** Exercise: read the file with the bracket pattern
    *   - open a Scanner
    *   - read the file line by line, every 100 millis
    *   - close the Scanner
    *   - if cancelled/throws error, close the Scanner
    */
  def openFileScanner(path: String): IO[Scanner] =
    IO(new Scanner(new FileReader(new File(path))))

  def readLineByLine(scanner: Scanner): IO[Unit] =
    if (scanner.hasNextLine)
      IO(scanner.nextLine()).debugging >> IO.sleep(
        100.millis
      ) >> readLineByLine(scanner)
    else IO.unit

  def bracketReadFile(path: String): IO[Unit] = {
    IO(s"Opening file at $path") *> openFileScanner(path)
      .bracket { scanner =>
        readLineByLine(scanner)
      } { scanner =>
        IO(s"closing file at $path").debugging *> IO(scanner.close()).void
      }
  }

  override def run: IO[Unit] = bracketReadFile("src/main/scala/com/rockthejvm/part3concurrency/Resources.scala").void

}
