package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Utils.create_exps
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.VerilogRename.verilogRenameN
import strober.core.ChainType
import mdf.macrolib.SRAMMacro
import java.io.{File, FileWriter}
import AddDaisyChains._

class DumpChains(
    dir: File,
    meta: StroberMetaData,
    srams: Map[String, SRAMMacro])
   (implicit param: config.Parameters) extends firrtl.passes.Pass {
  
  override def name = "[strober] Dump Daisy Chains"

  private def addPad(chainFile: FileWriter, cw: Int, dw: Int)(chainType: ChainType.Value) {
    (cw - dw) match {
      case 0 =>
      case pad => chainFile write s"${chainType.id} null ${pad} -1\n"
    }
  }

  private def loop(chainFile: FileWriter,
                   mod: String,
                   path: String)
                  (chainType: ChainType.Value)
                  (implicit daisyWidth: Int) {
    meta.chains(chainType) get mod match {
      case Some(chain) if !chain.isEmpty =>
        val id = chainType.id
        val (cw, dw) = (chain foldLeft (0, 0)){case ((chainWidth, dataWidth), s) =>
          val dw = dataWidth + (s match {
            case s: WDefInstance =>
              val sram = srams(s.module)
              (chainType: @unchecked) match {
                case ChainType.SRAM if mod == "smem_ext" || mod == "smem_0_ext" =>
                  chainFile write s"$id ${path}.${s.name}.ram 1 ${sram.depth}\n"
                  1
                case ChainType.SRAM =>
                  chainFile write s"$id ${path}.${s.name}.ram ${sram.width} ${sram.depth}\n"
                  sram.width
                case ChainType.Trace if mod == "smem_ext" || mod == "smem_0_ext" =>
                  val ports = sram.ports filter (_.output.nonEmpty)
                  (ports foldLeft 0){ (sum, p) =>
                    chainFile write s"$id ${path}.${s.name}.${p.output.get.name} 1 -1\n"
                    sum + 1
                  }
                case ChainType.Trace =>
                  val ports = sram.ports filter (_.output.nonEmpty)
                  (ports foldLeft 0){ (sum, p) =>
                    chainFile write s"$id ${path}.${s.name}.${p.output.get.name} ${p.width} -1\n"
                    sum + p.width
                  }
              }
            case s: DefMemory if !(debugRegs exists (s.name contains _)) =>
              val name = verilogRenameN(s.name)
              val width = bitWidth(s.dataType).toInt
              chainType match {
                case ChainType.RegFile =>
                  chainFile write s"$id $path.$name $width ${s.depth}\n"
                  width
                case ChainType.Regs => (((0 until s.depth) foldLeft 0){ (sum, i) =>
                  chainFile write s"$id $path.$name[$i] $width -1\n"
                  sum + width
                })
              }
            case s: DefRegister if !deadRegs(s.name) && !(debugRegs exists (s.name contains _)) =>
              val name = verilogRenameN(s.name)
              val width = bitWidth(s.tpe).toInt
              chainFile write s"$id $path.$name $width -1\n"
              width
          })
          val cw = (Stream from 0 map (chainWidth + _ * daisyWidth) dropWhile (_ < dw)).head
          (cw, dw)
        }
        addPad(chainFile, cw, dw)(chainType)
      case _ =>
    }
    meta.childInsts(mod) filter (x =>
       !(mod == "RocketTile" && x == "fpuOpt") &&
       !(mod == "NonBlockingDCache_dcache" && x == "dtlb")
    ) foreach (child => loop(chainFile, meta.instModMap(child, mod), s"${path}.${child}")(chainType))
  }

  def run(c: Circuit) = {
    implicit val daisyWidth = param(core.DaisyWidth)
    val chainFile = new FileWriter(new File(dir, s"${c.main}.chain"))
    ChainType.values.toList foreach loop(chainFile, c.main, c.main)
    chainFile.close
    c
  }
}
