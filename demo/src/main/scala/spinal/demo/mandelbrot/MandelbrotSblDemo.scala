package spinal.demo.mandelbrot

import spinal.core._
import spinal.lib._
import spinal.lib.bus.sbl._
import spinal.lib.com.uart._
import spinal.lib.graphic.Rgb
import spinal.lib.graphic.vga._


class MandelbrotSblDemo(frameAddressOffset: Int, p: MandelbrotCoreParameters, coreClk: ClockDomain, vgaMemoryClk: ClockDomain, vgaClk: ClockDomain) extends Component {
  val memoryBusConfig = SblConfig(30, 32)
  val rgbType = Rgb(8, 8, 8)

  val io = new Bundle {
    val uart = master(Uart())

    val mandelbrotWriteCmd = master Stream SblWriteCmd(memoryBusConfig)

    val vgaReadCmd = master Stream SblReadCmd(memoryBusConfig)
    val vgaReadRet = slave Flow SblReadRet(memoryBusConfig)

    val vga = master(Vga(rgbType))
  }

  val core = new ClockingArea(coreClk) {
    val uart = new Area {
      val ctrl = new UartCtrl()
      ctrl.io.clockDivider := BigInt((50e6 / 57.6e3 / 8).toLong)
      ctrl.io.config.dataLength := 7
      ctrl.io.config.parity := UartParityType.eParityNone
      ctrl.io.config.stop := UartStopType.eStop1bit
      ctrl.io.uart <> io.uart

//      ctrl.io.write.valid := False
//      ctrl.io.write.data := 0
    //TODO use reset generated
      val (flowFragment, _) = ctrl.io.read.toFlowFragmentBitsAndReset()


    }

    val mandelbrot = new Area {
      val core = new MandelbrotCore(p)
      core.io.cmdPort << uart.flowFragment
      core.io.retPort.toStreamBits() >> uart.ctrl.io.write

      //Take mandelbrot pixelResults and translate them into simple memory access
      val counter = Reg(UInt(32 bit)) init (0)
      when(io.mandelbrotWriteCmd.fire) {
        counter := counter + 1
        when(core.io.pixelResult.last) {
          counter := 0
        }
      }
      io.mandelbrotWriteCmd.translateFrom(core.io.pixelResult)((to, from) => {
        to.address := counter
        to.data := toBits(from.fragment.iteration)
      })
    }
  }

  val vga = new ClockingArea(vgaClk) {
    //Create VGA controller
    val ctrl = new VgaCtrl(rgbType, 12)
    ctrl.io.softReset := False
    ctrl.io.timings.setAs_h640_v480_r60 //Static timing for 640*480 pixel at 60HZ
    io.vga := ctrl.io.vga

    val newFrameEvent = ctrl.io.frameStart.genEvent
  }

  val vgaMemory = new ClockingArea(vgaMemoryClk) {
    //Create the DMA for this VGA controller
    val dma = new SblReadDma(memoryBusConfig)

    //Syncronise the frameStart event from the VGA to the current clock domain
    val frameStart = StreamCCByToggle(vga.newFrameEvent, vgaClk, vgaMemoryClk)
    //Translate it into a DMA command and send it into the DMA
    dma.io.cmd.translateFrom(frameStart)((to, from) => {
      to.offset := frameAddressOffset
      to.endAt := frameAddressOffset + p.screenResX * p.screenResY - 1
    })

    //Count pendings command on the vgaRead bus
    val pendingCmd = Reg(UInt(6 bit)) init (0)
    when(io.vgaReadCmd.fire !== io.vgaReadRet.fire) {
      when(io.vgaReadCmd.fire) {
        pendingCmd := pendingCmd + 1
      } otherwise {
        pendingCmd := pendingCmd - 1
      }
    }

    //Translate bus memory bus read Flow into a color read flow
    val colorFlow = Flow(rgbType).translateFrom(io.vgaReadRet)((to, from) => {
      to.assignFromBits(from.data)
    })

    //Translate the color Flow ino a Stream and syncronise/bufferise to the VgaClk by using a cross clock fifo
    val fifoSize = 512
    val (colorStream, colorStreamOccupancy) = colorFlow.toStream.queueWithPushOccupancy(fifoSize, vgaMemoryClk, vgaClk)

    //Halt the vga read cmd stream if there is to mutch pending command or if the fifo is near than full
    io.vgaReadCmd << dma.io.sblReadCmd.haltWhen(pendingCmd === (1<<widthOf(pendingCmd))-1 || RegNext(colorStreamOccupancy) > fifoSize - 128)

    vga.ctrl.io.colorStream << colorStream
  }
}

object MandelbrotSblDemo {
  def main(args: Array[String]) {
    SpinalVhdl({
      val vgaClock = ClockDomain("vga")
      val vgaMemoryClock = ClockDomain("vgaMemory")
      val coreClock = ClockDomain("core")
      new MandelbrotSblDemo(0, new MandelbrotCoreParameters(255, 1, 640, 480, 7, 36), coreClock, vgaMemoryClock, vgaClock)
    })
  }
}