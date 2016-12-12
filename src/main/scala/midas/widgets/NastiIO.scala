package midas
package widgets

// from rocketchip
import junctions._

import chisel3._
import chisel3.util._
import cde.{Parameters, Field}

abstract class MemModelConfig // TODO: delete it

class MemModelIO(implicit p: Parameters) extends WidgetIO()(p){
  val tNasti = Flipped(HostPort(new NastiIO, false))
  val tReset = Flipped(Decoupled(Bool()))
  val host_mem = new NastiIO
}

abstract class MemModel(implicit p: Parameters) extends Widget()(p){
  val io = IO(new MemModelIO)
  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    import CppGenerationUtils._
    sb.append(genMacro(this.getClass.getSimpleName))
  }
}

class NastiWidgetBase(implicit p: Parameters) extends MemModel()(p) {
  val arBuf = Module(new Queue(new NastiReadAddressChannel,   4, flow=true))
  val awBuf = Module(new Queue(new NastiWriteAddressChannel,  4, flow=true))
  val wBuf  = Module(new Queue(new NastiWriteDataChannel,    16, flow=true))
  val rBuf  = Module(new Queue(new NastiReadDataChannel,     16, flow=true))
  val bBuf  = Module(new Queue(new NastiWriteResponseChannel, 4, flow=true))
  
  def connect(memFire: Bool) = {
    io.tNasti.toHost.hReady := memFire
    io.tNasti.fromHost.hValid := memFire
    io.tReset.ready := memFire

    // Bad assumption: We have no outstanding read or write requests to host
    // during target reset. This will be handled properly in the fully fledged
    // memory model; i'm too lazy to properly handle this here.
    val targetReset = memFire && io.tReset.bits
    targetReset suggestName "targetReset"
    arBuf.reset := reset || targetReset
    awBuf.reset := reset || targetReset
    rBuf.reset := reset || targetReset
    bBuf.reset := reset || targetReset
    wBuf.reset := reset || targetReset

    val cycles = Reg(UInt(width=64))
    cycles suggestName "cycles"
    when (memFire) {
      cycles := Mux(targetReset, UInt(0), cycles + UInt(1))
    }

    (targetReset, cycles)
  }
}

// Widget to handle NastiIO efficiently when mem models are not available
class NastiWidget(implicit p: Parameters) extends NastiWidgetBase()(p) {
  val tNasti = io.tNasti.hBits
  val steps = Module(new Queue(UInt(width=32), 2))
  val stepCount = Reg(UInt(width=32))
  val readCount = Reg(UInt(width=32))
  val writeCount = Reg(UInt(width=32))
  val targetFire = io.tNasti.toHost.hValid && io.tNasti.fromHost.hReady && io.tReset.valid
  val memStall = !stepCount.orR && (readCount.orR || writeCount.orR)
  val memFire = targetFire && !memStall
  val (targetReset, cycles) = connect(memFire)

  steps.io.deq.ready := memStall
  when(reset || targetReset) {
    stepCount := UInt(0)
  }.elsewhen(steps.io.deq.valid && memStall) {
    stepCount := steps.io.deq.bits
  }.elsewhen(memFire && stepCount.orR) {
    stepCount := stepCount - UInt(1)
  }

  when(reset || targetReset) {
    readCount := UInt(0)
  }.elsewhen(tNasti.ar.fire() && memFire) {
    readCount := readCount + UInt(1)
  }.elsewhen(rBuf.io.enq.fire() && rBuf.io.enq.bits.last) {
    readCount := readCount - UInt(1)
  }

  when(reset || targetReset) {
    writeCount := UInt(0)
  }.elsewhen(tNasti.w.fire() && tNasti.w.bits.last && memFire) {
    writeCount := writeCount + UInt(1)
  }.elsewhen(bBuf.io.enq.fire()) {
    writeCount := writeCount - UInt(1)
  }

  // Requests
  tNasti.ar.ready := arBuf.io.enq.ready
  tNasti.aw.ready := awBuf.io.enq.ready
  tNasti.w.ready := wBuf.io.enq.ready
  arBuf.io.enq.valid := tNasti.ar.valid && memFire
  awBuf.io.enq.valid := tNasti.aw.valid && memFire
  wBuf.io.enq.valid := tNasti.w.valid && memFire
  arBuf.io.enq.bits := tNasti.ar.bits
  awBuf.io.enq.bits := tNasti.aw.bits
  wBuf.io.enq.bits := tNasti.w.bits

  // Response
  tNasti.r.bits := rBuf.io.deq.bits
  tNasti.b.bits := bBuf.io.deq.bits
  tNasti.r.valid := rBuf.io.deq.valid
  tNasti.b.valid := bBuf.io.deq.valid
  rBuf.io.deq.ready := tNasti.r.ready && memFire
  bBuf.io.deq.ready := tNasti.b.ready && memFire

  // Disable host_mem
  io.host_mem.ar.valid := Bool(false)
  io.host_mem.aw.valid := Bool(false)
  io.host_mem.w.valid := Bool(false)
  io.host_mem.r.ready := Bool(false)
  io.host_mem.b.ready := Bool(false)

  // Generate control register file
  val wdataChunks = (tNasti.w.bits.nastiXDataBits - 1) / io.ctrl.r.bits.nastiXDataBits + 1
  val rdataChunks = (tNasti.r.bits.nastiXDataBits - 1) / io.ctrl.w.bits.nastiXDataBits + 1
  genROReg(arBuf.io.deq.valid, "ar_valid")
  genROReg(arBuf.io.deq.bits.addr, "ar_addr")
  genROReg(arBuf.io.deq.bits.id, "ar_id")
  genROReg(arBuf.io.deq.bits.size, "ar_size")
  genROReg(arBuf.io.deq.bits.len, "ar_len")
  Pulsify(genWORegInit(arBuf.io.deq.ready, "ar_ready", Bool(false)), 1)

  genROReg(awBuf.io.deq.valid, "aw_valid")
  genROReg(awBuf.io.deq.bits.addr, "aw_addr")
  genROReg(awBuf.io.deq.bits.id, "aw_id")
  genROReg(awBuf.io.deq.bits.len, "aw_len")
  genROReg(awBuf.io.deq.bits.size, "aw_size")
  Pulsify(genWORegInit(awBuf.io.deq.ready, "aw_ready", Bool(false)), 1)

  genROReg(wBuf.io.deq.valid, "w_valid")
  genROReg(wBuf.io.deq.bits.strb, "w_strb")
  genROReg(wBuf.io.deq.bits.last, "w_last")
  Pulsify(genWORegInit(wBuf.io.deq.ready, "w_ready", Bool(false)), 1)
  val wdataRegs = Seq.fill(wdataChunks)(Reg(UInt()))
  val wdataAddrs = wdataRegs.zipWithIndex map {case (reg, i) =>
    reg := wBuf.io.deq.bits.data(io.ctrl.r.bits.nastiXDataBits*(i+1)-1,
                                 io.ctrl.r.bits.nastiXDataBits*i)
    attach(reg, s"w_data_$i")
  }

  Pulsify(genWORegInit(rBuf.io.enq.valid, "r_valid", Bool(false)), 1)
  genWOReg(rBuf.io.enq.bits.id, "r_id")
  genWOReg(rBuf.io.enq.bits.resp, "r_resp")
  genWOReg(rBuf.io.enq.bits.last, "r_last")
  genROReg(rBuf.io.enq.ready, "r_ready")
  val rdataRegs = Seq.fill(rdataChunks)(Reg(UInt()))
  val rdataAddrs = rdataRegs.zipWithIndex map {case (reg, i) => attach(reg, s"r_data_$i")}
  rBuf.io.enq.bits.data := Cat(rdataRegs.reverse)

  Pulsify(genWORegInit(bBuf.io.enq.valid, "b_valid", Bool(false)), 1)
  genWOReg(bBuf.io.enq.bits.id, "b_id")
  genWOReg(bBuf.io.enq.bits.resp, "b_resp")
  genROReg(bBuf.io.enq.ready, "b_ready")

  genROReg(targetFire, "tfire")
  genROReg(memStall, "stall")
  attachDecoupledSink(steps.io.enq, "steps")

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    import CppGenerationUtils._
    val name = getWName.toUpperCase
    sb.append(genArray(s"${name}_w_data", wdataAddrs map (off => UInt32(base + off)))) 
    sb.append(genArray(s"${name}_r_data", rdataAddrs map (off => UInt32(base + off)))) 
  }
}
