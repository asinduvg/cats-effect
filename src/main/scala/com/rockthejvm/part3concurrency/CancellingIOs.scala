package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}
import scala.concurrent.duration._

object CancellingIOs extends IOApp.Simple {

  import com.rockthejvm.utils._
  /*
    Cancelling IOs
      - fib.cancel
      - IO.race & other APIs
      - manual cancellation
   */

  val chainOfIOs: IO[Int] =
    IO("waiting").debugging >> IO.canceled >> IO(42).debugging

  // uncancelable
  // example: online store, payment processor
  // payment process must NOT be cancelled
  val specialPaymentSystem =
    (IO("Payment running, don't cancel me...").debugging >>
      IO.sleep(1.second) >>
      IO("Payment completed.").debugging).onCancel(
      IO("MEGA CANCEL OF DOOM!").debugging.void
    )

  val cancellationOfDoom = for {
    fib <- specialPaymentSystem.start
    _ <- IO.sleep(500.millis) >> fib.cancel
    _ <- fib.join
  } yield ()

  val atomicPayment = IO.uncancelable(_ => specialPaymentSystem) // "masking"
  val atomicPayment_v2 = specialPaymentSystem.uncancelable

  val noCancellationOfDoom = for {
    fib <- atomicPayment.start
    _ <- IO.sleep(500.millis) >> IO(
      "attempting cancellation..."
    ).debugging >> fib.cancel
    _ <- fib.join
  } yield ()

  /*
    The uncancelable API is more complex and more general.
    It takes a function from Poll[IO] to IO. In the example above, we aren't using the Poll instance.
    The Poll object can be used to mark sections within the returned effect which CAN BE CANCELLED.
   */

  /*
    Example: authentication service. Has two parts:
      - input password, can be cancelled, because otherwise we might block indefinitely on user input
      - verify password, CANNOT be cancelled once it's started
   */

  val inputPassword = IO("Input password:").debugging >> IO(
    "(typing password)"
  ).debugging >> IO.sleep(2.seconds) >> IO("RockTheJVM1!")
  val verifyPassword = (pw: String) =>
    IO("verifying...").debugging >> IO.sleep(2.seconds) >> IO(
      pw == "RockTheJVM1!"
    )

  val authFlow: IO[Unit] = IO.uncancelable { poll =>
    for {
      pw <- poll(inputPassword).onCancel(
        IO("Authentication timed out. Try again later.").debugging.void
      ) // this is cancellable
      verified <- verifyPassword(pw) // this is NOT cancellable
      _ <-
        if (verified)
          IO("Authentication successful.").debugging // this is NOT cancellable
        else IO("Authentication failed.").debugging
    } yield ()
  }

  val authProgram = for {
    authFib <- authFlow.start
    _ <- IO.sleep(3.seconds) >> IO(
      "Authentication timeout, attempting cancel..."
    ).debugging >> authFib.cancel
    _ <- authFib.join
  } yield ()

  /*
    Uncancelable calls are MASKS which suppress cancellation
    Poll calls are "gaps opened" in the uncancelable region
   */

  override def run: IO[Unit] = authProgram

}
