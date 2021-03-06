package spinal.lib.graphic.vga

import spinal.core._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3SlaveFactory}
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.bus.bmb.{Bmb, BmbAccessCapabilities, BmbAccessParameter, BmbImplicitPeripheralDecoder, BmbInterconnectGenerator, BmbParameter, BmbSlaveFactory}
import spinal.lib.bus.bsb.{Bsb, BsbInterconnectGenerator, BsbParameter}
import spinal.lib.graphic.{Rgb, RgbConfig, VideoDma}
import spinal.lib.generator._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.com.uart.BmbUartCtrl



object BmbVgaCtrl{
  def getBmbCapabilities(accessSource : BmbAccessCapabilities) = BmbSlaveFactory.getBmbCapabilities(
    accessSource,
    addressWidth = addressWidth,
    dataWidth = 32
  )
  def addressWidth = 8
}

case class BmbVgaCtrlParameter(rgbConfig: RgbConfig,
                               timingsWidth: Int = 12){

}


case class BmbVgaCtrl(p : BmbVgaCtrlParameter,
                      ctrlParameter : BmbParameter,
                      inputParameter : BsbParameter,
                      vgaCd : ClockDomain) extends Component{

  val io = new Bundle{
//    val input = slave(Stream(Fragment(Rgb(p.rgbConfig))))
    val input = slave(Bsb(inputParameter))
    val ctrl = slave(Bmb(ctrlParameter))
    val vga = master(Vga(p.rgbConfig))
  }

  val ctrl = BmbSlaveFactory(io.ctrl)

  val run = ctrl.createReadAndWrite(Bool,0x00) init(False)

  val vga = new ClockingArea(vgaCd) {
    val input = io.input.toStreamFragment(omitMask = true) //TODO
    val resized = Stream(Fragment(Bits(16 bits)))
    StreamFragmentWidthAdapter(input, resized)
    val adapted = Stream(Fragment(Rgb(p.rgbConfig)))
    adapted.arbitrationFrom(resized)
    adapted.last := resized.last
    adapted.r := U(resized.fragment(15-3, 4 bits))
    adapted.g := U(resized.fragment(10-3, 4 bits))
    adapted.b := U(resized.fragment( 4-3, 4 bits))

    val run = BufferCC(BmbVgaCtrl.this.run)
    val ctrl = VgaCtrl(p.rgbConfig, p.timingsWidth)
    ctrl.feedWith(adapted, resync = run.rise)
    io.input.ready setWhen(!run) //Flush
    ctrl.io.softReset := !run

    ctrl.io.vga <> io.vga
  }


  vga.ctrl.io.timings.driveFrom(ctrl, 0x40)
  vga.ctrl.io.timings.addTag(crossClockDomain)
}

case class BmbVgaCtrlGenerator(ctrlOffset : Handle[BigInt] = Unset)
                              (implicit val interconnect: BmbInterconnectGenerator, val bsbInterconnect : BsbInterconnectGenerator, decoder : BmbImplicitPeripheralDecoder = null) extends Generator{

  val ctrl = produce(logic.io.ctrl)
  val input = produce(logic.io.input)
  val output = produce(logic.io.vga)
  val parameter = createDependency[BmbVgaCtrlParameter]
  val vgaCd = createDependency[ClockDomain]

  val logic : Handle[BmbVgaCtrl] = add task BmbVgaCtrl(
    p              = parameter,
    ctrlParameter  = accessRequirements.toBmbParameter(),
    inputParameter = BsbParameter(
      byteCount = is.byteCount,
      sourceWidth = is.sourceWidth,
      sinkWidth = is.sinkWidth,
      withMask = is.withMask
    ),
    vgaCd          = vgaCd
  )

  val accessSource = Handle[BmbAccessCapabilities]
  val accessRequirements = createDependency[BmbAccessParameter]
  interconnect.addSlave(
    accessSource = accessSource,
    accessCapabilities = accessSource.derivate(BmbVgaCtrl.getBmbCapabilities),
    accessRequirements = accessRequirements,
    bus = ctrl,
    mapping = ctrlOffset.derivate(SizeMapping(_, 1 << BmbVgaCtrl.addressWidth))
  )
  if(decoder != null) interconnect.addConnection(decoder.bus, ctrl)
  val is = bsbInterconnect.addSlave(input).onClockDomain(vgaCd)
  dependencies += List(is.byteCount, is.sourceWidth, is.withMask)
  is.sinkWidth.load(0)

  def withRegisterPhy(withColorEn : Boolean) = output.produce{
    val reg = out(vgaCd.get(Reg(Vga(output.rgbConfig, false))))
    reg.assignSomeByName(output)
    when(!output.colorEn){
      reg.color.clear()
    }
    reg
  }

}
