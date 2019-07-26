package beagle

import chisel3._

import freechips.rocketchip.config.{Field, Parameters, Config}
import freechips.rocketchip.subsystem.{ExtMem, RocketTilesKey, BankedL2Key, WithJtagDTM, WithNMemoryChannels, SystemBusKey, MemoryBusKey, ControlBusKey, CacheBlockBytes}
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

import hwacha.{Hwacha}

import boom.system.{BoomTilesKey}

import systolic.{SystolicArray, SystolicArrayConfig, Dataflow}

import beagle.serdes._

// -------
// CONFIGS
// -------

/**
 * Heterogeneous (BOOM + Rocket)
 *
 * USED FOR SIMULATION AND FAST BUILDS
 */
class BeagleBoomRocketSimConfig extends Config(
  // uncore mixins
  new example.WithBootROM ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new WithBeagleL2 ++
  new WithBeagleSimChanges ++
  new WithBeagleChanges ++
  new WithBeagleSiFiveBlocks ++
  new WithJtagDTM ++
  new WithHierTiles ++
  new WithNMemoryChannels(2) ++
  new WithBeagleSerdesChanges ++
  new WithGenericSerdes ++
  new boom.system.WithRenumberHarts ++
  // make tiles support different clocks
  new boom.system.WithAsynchronousBoomTiles(4, 4) ++
  new freechips.rocketchip.subsystem.WithAsynchronousRocketTiles(4, 4) ++
  // boom mixins
  new boom.common.WithRVC ++
  new WithMegaBeagleBooms ++
  new boom.common.BaseBoomConfig ++
  new boom.system.WithNBoomCores(1) ++
  // rocket mixins
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++
  // subsystem mixin
  new freechips.rocketchip.system.BaseConfig)

/**
 * Heterogeneous ((Mega BOOM + Hwacha) + (Rocket + Systolic))
 *
 * USED FOR TAPEOUT
 */
class MegaBeagleConfig extends Config(
  // uncore mixins
  new example.WithBootROM ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new WithBeagleL2 ++
  new WithBeagleChanges ++
  new WithBeagleSiFiveBlocks ++
  new WithJtagDTM ++
  new WithNMemoryChannels(2) ++
  new WithBeagleSerdesChanges ++
  new WithBeagleSerdes ++

  // note: THIS MUST BE ABOVE hwacha.DefaultHwachaConfig TO WORK
  new example.WithMultiRoCC ++ // attach particular RoCC accelerators based on the hart
  new example.WithMultiRoCCHwacha(0) ++ // add a hwacha to just boom
  new WithMultiRoCCSystolic(1) ++ // add a systolic to just rocket
  new boom.system.WithRenumberHarts ++ // renumber harts with boom starting at 0 then rocket

  // hwacha parameter setup mixins
  new hwacha.DefaultHwachaConfig ++

  // make tiles support different clocks
  new boom.system.WithAsynchronousBoomTiles(4, 4) ++
  new freechips.rocketchip.subsystem.WithAsynchronousRocketTiles(4, 4) ++

  // rocket mixins
  new WithMiniRocketCore ++

  // boom mixins
  new boom.common.WithRVC ++
  new WithMegaBeagleBooms ++
  new boom.common.BaseBoomConfig ++
  new boom.system.WithNBoomCores(1) ++

  // subsystem mixin
  new freechips.rocketchip.system.BaseConfig)

/**
 * Heterogeneous ((Mega BOOM + Hwacha) + (Rocket + Systolic))
 *
 * USED FOR SIMULATION
 */
class MegaBeagleSimConfig extends Config(
  // for faster simulation
  new WithBeagleSimChanges ++
  // uncore mixins
  new example.WithBootROM ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new WithBeagleL2 ++
  new WithBeagleChanges ++
  new WithBeagleSiFiveBlocks ++
  new WithJtagDTM ++
  new WithNMemoryChannels(2) ++
  new WithBeagleSerdesChanges ++
  new WithGenericSerdes ++

  // note: THIS MUST BE ABOVE hwacha.DefaultHwachaConfig TO WORK
  new example.WithMultiRoCC ++ // attach particular RoCC accelerators based on the hart
  new example.WithMultiRoCCHwacha(0) ++ // add a hwacha to just boom
  new WithMultiRoCCSystolic(1) ++ // add a systolic to just rocket
  new boom.system.WithRenumberHarts ++ // renumber harts with boom starting at 0 then rocket

  // hwacha parameter setup mixins
  new hwacha.DefaultHwachaConfig ++

  // make tiles support different clocks
  new boom.system.WithAsynchronousBoomTiles(4, 4) ++
  new freechips.rocketchip.subsystem.WithAsynchronousRocketTiles(4, 4) ++

  // rocket mixins
  new WithMiniRocketCore ++

  // boom mixins
  new boom.common.WithRVC ++
  new WithMegaBeagleBooms ++
  new boom.common.BaseBoomConfig ++
  new boom.system.WithNBoomCores(1) ++

  // subsystem mixin
  new freechips.rocketchip.system.BaseConfig)


