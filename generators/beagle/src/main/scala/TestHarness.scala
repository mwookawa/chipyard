package beagle

import chisel3._
import chisel3.util._
import chisel3.experimental._

import firrtl.transforms.{BlackBoxResourceAnno, BlackBoxSourceHelper}

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.util.GeneratorApp
import freechips.rocketchip.devices.tilelink.{TLTestRAM}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.{AsyncQueue, ShiftRegInit}
import freechips.rocketchip.devices.debug.{SimJTAG}
import freechips.rocketchip.jtag.{JTAGIO}

import testchipip.{SerialAdapter, SimSerial, TLSerdesser}

import hbwif.tilelink.{HbwifTLKey, HbwifNumLanes, TLController, TLControllerPusher, TLControllerWritePattern, GenericHbwifModule}
import hbwif.{ClockToDifferential}

class BeagleTestHarness(implicit val p: Parameters) extends Module
{
  val io = IO(new Bundle {
    val success = Output(Bool())
  })

   // force Chisel to rename module
  override def desiredName = "TestHarness"

  val inner = Module(LazyModule(new BeagleTestHarnessInner).module)
  io.success := inner.io.success
}

class BeagleTestHarnessInner(implicit p: Parameters) extends LazyModule
{
  val adapter = LazyModule(new SerialAdapter(1 << 4))

  val harness_rams = p(HbwifTLKey).managerAddressSet.map(addrSet =>
    LazyModule(new TLTestRAM(
      address = addrSet,
      beatBytes = p(ExtMem).get.master.beatBytes,
      trackCorruption = false)))

  println("Harnessside")
  val lbwif = LazyModule(new TLSerdesser(
    w = p(LbwifBitWidth),
    clientParams = TLClientParameters(
      name = "tl_serdes_control",
      sourceId = IdRange(0, (1 << 13)), // match DUT source bits
      requestFifo = true),
    managerParams = TLManagerParameters(
      address = Seq(AddressSet(p(ExtMem).get.master.base, p(ExtMem).get.master.size-1)),
      regionType = RegionType.UNCACHED, // cacheable
      executable = true,
      fifoId = Some(0),
      supportsGet        = TransferSizes(1, p(CacheBlockBytes)),
      supportsPutFull    = TransferSizes(1, p(CacheBlockBytes)),
      supportsPutPartial = TransferSizes(1, p(CacheBlockBytes)),
      supportsAcquireT   = TransferSizes(1, p(CacheBlockBytes)),
      supportsAcquireB   = TransferSizes(1, p(CacheBlockBytes)),
      supportsArithmetic = TransferSizes(1, p(CacheBlockBytes))),
     beatBytes = p(ExtMem).get.master.beatBytes,
     endSinkId = 1<<6))

  val hbwif = LazyModule(new GenericHbwifModule()(p.alterPartial({
    case HbwifTLKey => {
      p(HbwifTLKey).copy(
        numXact = (1 << 13),
        clientPort = true,
        clientTLUH = false,
        clientTLC = false,
        managerTLUH = false,
        managerTLC = false)
    }
  })))

  val hbwif_xbar = LazyModule(new TLXbar)

  val hbwif_nodes = new {
    val config = TLClientHelper("SimHbwifConfigPusher")
    val manager = TLClientHelper("SimHbwifManager", n = p(HbwifNumLanes))
    hbwif_xbar.node := config
    hbwif.managerNode :*= manager
  }
  hbwif.configNodes.foreach { _ := hbwif_xbar.node }

  val mem_xbar = LazyModule(new TLXbar)
  harness_rams.foreach { ram =>
    ram.node := TLFragmenter(p(ExtMem).get.master.beatBytes, p(CacheBlockBytes)) := mem_xbar.node
  }

  lbwif.managerNode := TLBuffer() := adapter.node

  // connect the hbwif/lbwif to the ram
  for (i <- 0 until p(HbwifNumLanes)) {
    mem_xbar.node := hbwif.clientNode
  }
  mem_xbar.node := TLBuffer() := lbwif.clientNode

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val success = Output(Bool())
    })

    val dut = Module(new BeagleChipTop)

    // Setup the HBWIF
    hbwif.module.hbwifResets.foreach { _ := reset }
    hbwif.module.hbwifRefClocks.foreach { _ := clock }
    hbwif.module.resetAsync := reset

    private val settings = Seq(
      TLControllerWritePattern("bert_enable", 0),
      TLControllerWritePattern("mem_mode_enable", 1))

    private val (out, edge) = hbwif_nodes.config.out.head
    val hbwifPusher = Module(new TLControllerPusher(edge,
      hbwif.module.addrmaps.zip(p(HbwifTLKey).configAddressSets).flatMap {
          case (m, a) => TLController.toPattern(m, a.base, settings)
      }
    ))
    hbwifPusher.io.start := Timer(50)
    out <> hbwifPusher.io.tl

    hbwif_nodes.manager.out.foreach { _._1.tieoff() }
    // DONE: Setup the HBWIF

    val harness_clk_divider = Module(new testchipip.ClockDivider(2))
    harness_clk_divider.io.divisor := 1.U
    val harness_slow_clk = harness_clk_divider.io.clockOut

    val harness_fast_clk = ClockToDifferential(clock)

    dut.reset := reset
    dut.boot := true.B
    dut.single_clks.foreach { _ := harness_slow_clk }
    dut.diff_clks.foreach { _ := DontCare }
    dut.diff_clks.foreach { diff_clk =>
      attach(diff_clk.p, harness_fast_clk.p)
      attach(diff_clk.n, harness_fast_clk.n)
    }
    dut.bh_clk_sel := 0.U
    dut.rs_clk_sel := 0.U
    dut.uncore_clk_sel := 0.U
    dut.gpio := DontCare
    dut.i2c  := DontCare
    dut.spi  := DontCare
    dut.uart := DontCare
    dut.jtag := DontCare
    dut.hbwif.tx <> hbwif.module.rx
    dut.hbwif.rx <> hbwif.module.tx
    dut.hbwif_diff_clks.foreach { _ := DontCare }
    dut.hbwif_diff_clks.foreach { diff_clk =>
      attach(diff_clk.p, harness_fast_clk.p)
      attach(diff_clk.n, harness_fast_clk.n)
    }

    // SimSerial <-> SerialAdapter <-> Serdes <--ChipConnection--> Lbwif

    val sim = Module(new SimSerial(SerialAdapter.SERIAL_IF_WIDTH))

    sim.io.clock := clock
    sim.io.reset := reset

    val lbwif_tx_queue = Module(new AsyncQueue(chiselTypeOf(lbwif.module.io.ser.out.bits)))
    val lbwif_rx_queue = Module(new AsyncQueue(chiselTypeOf(lbwif.module.io.ser.in.bits)))

    lbwif_tx_queue.io.enq <> lbwif.module.io.ser.out
    lbwif_tx_queue.io.enq_clock <> clock
    lbwif_tx_queue.io.enq_reset <> reset

    lbwif.module.io.ser.in <> lbwif_rx_queue.io.deq
    lbwif_rx_queue.io.deq_clock <> clock
    lbwif_rx_queue.io.deq_reset <> reset

    dut.lbwif_serial.in <> lbwif_tx_queue.io.deq
    lbwif_tx_queue.io.deq_clock := dut.lbwif_clk_out
    lbwif_tx_queue.io.deq_reset := reset // TODO: should be onchip reset

    lbwif_rx_queue.io.enq <> dut.lbwif_serial.out
    lbwif_rx_queue.io.enq_clock := dut.lbwif_clk_out
    lbwif_rx_queue.io.enq_reset := reset // TODO: should be onchip reset

    sim.io.serial.out <> Queue(adapter.module.io.serial.out)
    adapter.module.io.serial.in <> Queue(sim.io.serial.in)

    // connect the jtag port
    val jtag_success = Wire(Bool())
    val jtag_io = Wire(new JTAGIO())

    dut.jtag.TCK.i.ival := jtag_io.TCK.asUInt.asBool
    dut.jtag.TMS.i.ival := jtag_io.TMS
    dut.jtag.TDI.i.ival := jtag_io.TDI
    jtag_io.TRSTn.foreach{t =>
      dut.jtag.TRSTn.get.i.ival := t
    }
    jtag_io.TDO.data := dut.jtag.TDO.o.oval
    jtag_io.TDO.driven := dut.jtag.TDO.o.oe

    dut.jtag.TDO.i.ival := DontCare

    Module(new SimJTAG(tickDelay=3)).connect(jtag_io, clock, reset.asUInt.asBool, ~reset.asUInt.asBool, jtag_success)

    // connect gpios in loopback
    dut.gpio.pins(3).i.ival := Mux(dut.gpio.pins(3).o.ie, dut.gpio.pins(0).o.oe && dut.gpio.pins(0).o.oval, 0.U)
    dut.gpio.pins(4).i.ival := Mux(dut.gpio.pins(3).o.ie, dut.gpio.pins(1).o.oe && dut.gpio.pins(1).o.oval, 0.U)
    dut.gpio.pins(5).i.ival := Mux(dut.gpio.pins(3).o.ie, dut.gpio.pins(2).o.oe && dut.gpio.pins(2).o.oval, 0.U)

    // connect the uart
    dut.uart.rxd.i.ival := Mux(dut.uart.rxd.o.ie, dut.uart.txd.o.oe && dut.uart.txd.o.oval, 0.U)

    // connect the exit signal

    io.success := sim.io.exit || jtag_success
  }
}

object TLClientHelper {
  def apply(name: String, sourceId: IdRange = IdRange(0,1), n: Int = 1)(implicit valName: ValName): TLClientNode = {
    val params = TLClientPortParameters(Seq(TLClientParameters(name, sourceId)))
    TLClientNode(Seq.fill(n)(params))
  }
}

object Timer {
  def apply(init: Int): Bool = {
    val count = RegInit(init.U)
    val done = (count === 0.U)
    when (!done) {
      count := count - 1.U
    }
    done
  }
}

class TLSinkSetter(endSinkId: Int)(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(managerFn = { m => m.copy(endSinkId = endSinkId) })
  lazy val module = new LazyModuleImp(this) {
    // FIXME: bulk connect
    def connect[T <: TLBundleBase](out: DecoupledIO[T], in: DecoupledIO[T]) {
      out.valid := in.valid
      out.bits := in.bits
      in.ready := out.ready
    }

    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      connect(out.a, in.a) // out.a <> in .a
      connect(in.d, out.d) // in .d <> out.d
      if (edgeOut.manager.anySupportAcquireB && edgeOut.client.anySupportProbe) {
        connect(in.b, out.b) // in .b <> out.b
        connect(out.c, in.c) // out.c <> in .c
        connect(out.e, in.e) // out.e <> in .e
      } else {
        in.b.valid := false.B
        in.c.ready := true.B
        in.e.ready := true.B
        out.b.ready := true.B
        out.c.valid := false.B
        out.e.valid := false.B
      }
    }
  }
}

object TLSinkSetter {
  def apply(endSinkId: Int)(implicit p: Parameters): TLNode = {
    val widener = LazyModule(new TLSinkSetter(endSinkId))
    widener.node
  }
}
