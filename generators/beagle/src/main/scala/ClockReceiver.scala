package beagle

import chisel3._
import chisel3.experimental.{Analog}

class ClockReceiverIO extends Bundle
{
  val inn = Analog(1.W)
  val inp = Analog(1.W)
  val out = Output(Clock())
}

class ClockReceiver extends BlackBox
{
  val io = IO(new ClockReceiverIO)
  override def desiredName = "DIFFAMP_SELFBIASED"
}

