package midas
package platform

import util.ParameterizedBundle // from rocketchip
import chisel3._
import chisel3.util._
import junctions._
import cde.{Parameters, Field}

abstract class PlatformShim extends Module {
  def top: midas.core.FPGATop
  def vtype: String
  def headerConsts: Seq[(String, Int)]
  def genHeader(sb: StringBuilder, target: String) {
    sb append "static const char* const TARGET_NAME = \"%s\";\n".format(target)
    sb append s"#define PLATFORM_TYPE $vtype\n"
    if (top.sim.enableSnapshot) sb append "#define ENABLE_SNAPSHOT\n"
    if (top.sim.enableMemModel) sb append "#define ENABLE_MEMMODEL\n"
    top.genHeader(sb)(top.sim.channelWidth)
  }
}

case object MasterNastiKey extends Field[NastiParameters]
case object SlaveNastiKey extends Field[NastiParameters]

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new NastiIO()(p alter Map(NastiKey -> p(MasterNastiKey))))
  val slave  = new NastiIO()(p alter Map(NastiKey -> p(SlaveNastiKey)))
}

class ZynqShim(simIo: midas.core.SimWrapperIO)
              (implicit p: Parameters) extends PlatformShim {
  val io = IO(new ZynqShimIO)
  val top = Module(new midas.core.FPGATop(simIo))
  val vtype = "VZynqShim"
  val headerConsts = List(
    "MMIO_WIDTH" -> p(MasterNastiKey).dataBits / 8,
    "MEM_WIDTH"  -> p(SlaveNastiKey).dataBits / 8
  ) ++ top.headerConsts

  top.io.ctrl <> io.master
  io.slave <> top.io.mem
}