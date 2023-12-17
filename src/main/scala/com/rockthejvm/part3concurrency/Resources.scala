package com.rockthejvm.part3concurrency

import cats.effect.kernel.Outcome.{Errored, Succeeded, Canceled}
import cats.effect.{IO, IOApp, Resource}

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

  /** Resources
    */

  def connFromConfig(path: String): IO[Unit] =
    openFileScanner(path)
      .bracket { scanner =>
        // acquire a connection based on the file
        IO(new Connection(scanner.nextLine())).bracket { conn =>
          conn.open() >> IO.never
        }(conn => conn.close().void)
      }(scanner => IO("closing file").debugging >> IO(scanner.close()))
    // nesting resources are tedious

  val connectionResource =
    Resource.make(IO(new Connection("rockthejvm.com")))(conn =>
      conn.close().void
    )
  // ... at a later part of your code
  val resourceFetchURL = for {
    fib <- connectionResource.use(conn => conn.open() >> IO.never).start
    _ <- IO.sleep(1.second) >> fib.cancel
  } yield ()

  // resources are equivalent to brackets
  val simpleResource = IO("some resource")
  val usingResource: String => IO[String] = string =>
    IO(s"using the string: $string").debugging
  val releaseResource: String => IO[Unit] = string =>
    IO(s"finalizing the string: $string").debugging.void

  val usingResourceWithBracket =
    simpleResource.bracket(usingResource)(releaseResource)
  val usingResourceWithResource =
    Resource.make(simpleResource)(releaseResource).use(usingResource)

  /** Exercise: Read a text file with one line every 100 millis, using Resource
    * (refactor the bracket exercise to use Resource)
    */

  def getResourceFromFile(path: String) = Resource.make(openFileScanner(path)) {
    scanner =>
      IO(s"closing file at $path").debugging >> IO(scanner.close())
  }

  def resourceReadFile(path: String) =
    IO(s"opening file at $path") >>
      getResourceFromFile(path).use { scanner =>
        readLineByLine(scanner)
      }

  def cancelReadFile(path: String) = for {
    fib <- resourceReadFile(path).start
    _ <- IO.sleep(2.seconds) >> fib.cancel
  } yield ()

  // nested resources
  def connFromConfResource(path: String) =
    Resource
      .make(IO("opening file").debugging >> openFileScanner(path))(scanner =>
        IO("closing file").debugging >> IO(scanner.close())
      )
      .flatMap(scanner =>
        Resource.make(IO(new Connection(scanner.nextLine())))(conn =>
          conn.close().void
        )
      )

  // equivalent
  def connFromConfResourceClean(path: String) = for {
    scanner <- Resource.make(
      IO("opening file").debugging >> openFileScanner(path)
    )(scanner => IO("closing file").debugging >> IO(scanner.close()))
    conn <- Resource.make(IO(new Connection(scanner.nextLine())))(conn =>
      conn.close().void
    )
  } yield conn

  val openConnection =
    connFromConfResourceClean("src/main/resources/connection.txt").use(conn =>
      conn.open() >> IO.never
    )

  val cancelledConnection = for {
    fib <- openConnection.start
    _ <- IO.sleep(1.second) >> IO("cancelling").debugging >> fib.cancel
  } yield ()

  // connection + file will close automatically

  // finalizers to regular IOs
  val ioWithFinalizer = IO("some resource").debugging.guarantee(
    IO("freeing resource").debugging.void
  )
  val ioWithFinalizer_V2 = IO("some resource").debugging.guaranteeCase {
    case Succeeded(fa) =>
      fa.flatMap(result => IO(s"releasing resource: $result").debugging).void
    case Errored(e) => IO("nothing to release").debugging.void
    case Canceled() =>
      IO("resource got cancelled, releasing what's left").debugging.void
  }

  override def run: IO[Unit] = ioWithFinalizer.void

}
