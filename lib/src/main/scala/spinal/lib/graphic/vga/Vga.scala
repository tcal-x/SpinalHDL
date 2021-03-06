package spinal.lib.graphic.vga

import spinal.core._
import spinal.lib._
import spinal.lib.graphic.{RgbConfig, Rgb}


case class Vga (rgbConfig: RgbConfig, withColorEn : Boolean = true) extends Bundle with IMasterSlave{
  val vSync = Bool
  val hSync = Bool

  val colorEn = withColorEn generate Bool
  val color = Rgb(rgbConfig)

  override def asMaster() = this.asOutput()
  override def asSlave() = this.asInput()
}


