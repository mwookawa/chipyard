package beagle

import chisel3._

import freechips.rocketchip.config.{Field, Parameters, Config}
import freechips.rocketchip.subsystem.{WithInclusiveCache, ExtMem, RocketTilesKey, BankedL2Key, WithJtagDTM, WithRationalRocketTiles, WithNMemoryChannels, WithNBanks, SystemBusKey, MemoryBusKey, ControlBusKey, CacheBlockBytes}
import freechips.rocketchip.diplomacy.{LazyModule, ValName, AddressSet}
import freechips.rocketchip.tile.{LazyRoCC, BuildRoCC, OpcodeSet, TileKey, RocketTileParams}
import freechips.rocketchip.rocket.{RocketCoreParams, BTBParams, DCacheParams, ICacheParams, MulDivParams}

import sifive.blocks.devices.gpio._
import sifive.blocks.devices.spi._
import sifive.blocks.devices.uart._
import sifive.blocks.devices.i2c._
import sifive.blocks.devices.jtag._

import hbwif.tilelink._
import hbwif._

import hwacha.{Hwacha, HwachaStagesDFMA, HwachaStagesSFMA, HwachaStagesHFMA, HwachaStagesIMul, HwachaConfPrec}

import boom.system.{BoomTilesKey}
import boom.exu.{IssueParams}
import boom.ifu.{FtqParameters}
import boom.bpu.{GShareParameters, BoomBTBParameters}
import boom.common._

import systolic.{SystolicArray, SystolicArrayConfig, Dataflow}

import example.{MultiRoCCKey}

// --------------
// Special MIXINS
// --------------

/**
 * Add a inclusive L2
 */
class WithBeagleL2 extends Config(
  new WithInclusiveCache(
    nBanks = 4,
    capacityKB = 1024)
)

/**
 * Setup general BEAGLE parameters
 */
class WithBeagleChanges extends Config((site, here, up) => {
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = 16)
  case MemoryBusKey => up(MemoryBusKey).copy(beatBytes = 8)
  case ControlBusKey => {
    val cBus = up(ControlBusKey)
    cBus.copy(errorDevice = cBus.errorDevice.map(e => e.copy(maxTransfer=64)))
  }
  case BeaglePipelineResetDepth => 5
  case HbwifPipelineResetDepth => 5
  case CacheBlockStriping => 4
  case LbwifBitWidth => 4
  case PeripheryBeagleKey => BeagleParams(scrAddress = 0x110000)
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(fpu = r.core.fpu.map(_.copy(sfmaLatency = 4, dfmaLatency = 5))))
  }
  case HwachaStagesDFMA => 5
  case HwachaStagesSFMA => 4
  case HwachaStagesHFMA => 3
  case HwachaStagesIMul => 4
  case HwachaConfPrec => true
})

/**
 * Increase the LBWIF size and reduce the dividers
 */
class WithBeagleSimChanges extends Config((site, here, up) => {
  case LbwifBitWidth => 32
  case PeripheryBeagleKey => up(PeripheryBeagleKey).copy(
    uncoreClkDivInit = 1, // %2
    bhClkDivInit = 1, // %2
    rsClkDivInit = 1, // %2
    lbwifClkDivInit = 1, // %2
    uncoreClkPassSelInit = 1, // div clk
    bhClkPassSelInit = 0, // undiv clk
    rsClkPassSelInit = 0, // undiv clk
    lbwifClkPassSelInit = 0) // undiv clk
})

/**
 * Mixin for adding external I/O
 */
class WithBeagleSiFiveBlocks extends Config((site, here, up) => {
  case PeripheryGPIOKey => Seq(GPIOParams(address = 0x9000, width = 6))
  case PeripherySPIKey => Seq(SPIParams(rAddress = 0xa000))
  case PeripheryI2CKey => Seq(I2CParams(address = 0xb000))
  case PeripheryUARTKey => Seq(UARTParams(address = 0xc000))
})

/**
 * Add buffers around the tiles
 */
class WithHierTiles extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(boundaryBuffers = true) }
  case BoomTilesKey => up(BoomTilesKey, site) map { b =>
    b.copy(boundaryBuffers = true) }
})

/**
 * Mixin to change generic serdes parameters
 *
 * banks should be 1
 * lanes should be 2 or 4
 * clientPort = false
 * managerTL(UH\C) = false
 */
class WithBeagleSerdesChanges extends Config((site, here, up) => {
  case HbwifNumLanes => 2
  case HbwifTLKey => up(HbwifTLKey, site).copy(
    managerAddressSet = AddressSet.misaligned(site(ExtMem).get.master.base, site(ExtMem).get.master.size),
    numBanks = 2,
    numXact = 16,
    clientPort = false,
    managerTLUH = false,
    managerTLC = false)
})

// ---------------------
// Systolic Array Mixins
// ---------------------

/**
 * Systolic Array Params
 */
object SystolicConfigs {
  val defaultConfig = SystolicArrayConfig(
    tileRows = 1,
    tileColumns = 1,
    meshRows = 16,
    meshColumns = 16,
    ld_str_queue_length = 10,
    ex_queue_length = 10,
    sp_banks = 4,
    sp_bank_entries = 256 * 1024 * 8 / (4 * 16 * 8), // has to be a multiply of meshRows*tileRows
    sp_width = 8 * 16, // has to be meshRows*tileRows*dataWidth // TODO should this be changeable?
    shifter_banks = 1, // TODO add separate parameters for left and up shifter banks
    depq_len = 65536,
    dataflow = Dataflow.BOTH,
    acc_rows = 64 * 1024 * 8 / (16 * 32),
    mem_pipeline = 1,
    dma_maxbytes = 128, // TODO get this from cacheblockbytes
    dma_buswidth = 128, // TODO get this from SystemBusKey
    inputType = SInt(8.W),
    outputType = SInt(19.W),
    accType = SInt(32.W)
  )
}

/**
 * Mixin to add SystolicArrays to cores
 *
 * For ex:
 *   Core 0, 1, 2, 3 have been defined earlier
 *     with hardIds of 0, 1, 2, 3 respectively
 *   And you call WithMultiRoCCSystolicWithHarts(0,1)
 *   Then Core 0 and 1 will get a SystolicArray
 *
 * @param harts Seq of harts to specify which will get a SystolicArray
 */
class WithMultiRoCCSystolic(harts: Int*) extends Config((site, here, up) => {
  case MultiRoCCKey => {
    require(harts.max <= ((up(RocketTilesKey, site).length + up(BoomTilesKey, site).length) - 1))
    up(MultiRoCCKey, site) ++ harts.distinct.map{ i =>
      (i -> Seq((p: Parameters) => {
        implicit val q = p
        implicit val v = implicitly[ValName]
        LazyModule(new SystolicArray(OpcodeSet.custom3, SystolicConfigs.defaultConfig)).suggestName("systolic_array")
      }))
    }
  }
})

/**
 * Mixin to add a new Rocket core with hartId of X (last hartId in the system)
 */
class WithMiniRocketCore extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) :+
    RocketTileParams(
      name = Some("rocket_tile"),
      core = RocketCoreParams(
        useVM = true,
        mulDiv = Some(MulDivParams(mulUnroll = 8))),
      btb = Some(BTBParams(nEntries = 14, nRAS = 2)),
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusKey).beatBits,
        blockBytes = site(CacheBlockBytes),
        nSets = 64,
        nWays = 4)),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusKey).beatBits,
        blockBytes = site(CacheBlockBytes),
        nSets = 64,
        nWays = 4)),
      hartId = up(BoomTilesKey, site).length + up(RocketTilesKey, site).length)
})

// ---------------------
// BOOM Mixins
// ---------------------

/**
 * Beagle BOOM design point
 */
class WithMegaBeagleBooms extends Config((site, here, up) => {
   case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
      core = b.core.copy(
         fetchWidth = 4,
         decodeWidth = 3,
         numRobEntries = 120,
         issueParams = Seq(
            IssueParams(issueWidth=1, numEntries=24, iqType=IQT_MEM.litValue, dispatchWidth=3),
            IssueParams(issueWidth=2, numEntries=24, iqType=IQT_INT.litValue, dispatchWidth=3),
            IssueParams(issueWidth=1, numEntries=24, iqType=IQT_FP.litValue , dispatchWidth=3)),
         numIntPhysRegisters = 128,
         numFpPhysRegisters = 96,
         numLdqEntries = 24,
         numStqEntries = 24,
         maxBrCount = 16,
         useNewFetchBuffer = true,
         numFetchBufferEntries = 24,
         ftq = FtqParameters(nEntries=32),
         btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=512, nWays=4, nRAS=16, tagSz=13),
         bpdBaseOnly = None,
         gshare = Some(GShareParameters(historyLength=13, numSets=8192)),
         tage = None,
         bpdRandom = None,
         nPerfCounters = 29,
         fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=5, dfmaLatency=5, divSqrt=true))),
      btb = Some(BTBParams(nEntries = 0, updatesOutOfOrder = true)),
      dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=8, nMSHRs=4, nTLBEntries=16)),
      icache = Some(ICacheParams(fetchBytes = 4*4, rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=8))
    )}
})
