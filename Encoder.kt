package gov.bart.fce.bas.encoder

import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log.getStackTraceString
import com.google.android.things.pio.PeripheralManager
import com.google.android.things.pio.SpiDevice
import gov.bart.fce.bas.BasLogger
import gov.bart.fce.bas.LifeCycleAwareClass
import gov.bart.fce.bas.barrier.BarrierState
import java.io.File
import java.io.IOException
import java.lang.Math.abs
import kotlin.experimental.and

class Encoder(private val ownerName: String, private val spiDeviceName: String) : LifeCycleAwareClass {

    private val logger = BasLogger.getInstance()
    val TAG: String = "${ownerName}-Encoder"

    private val debugMode: Boolean = spiDeviceName == "TEST"

    var gatherCenterEncoderValues = false
        private set
    var centerValue: Int? = null
        private set

    var gatheredCenterValues = mutableListOf<Int>()
        private set
    var centerValueFound: Boolean = false
        private set

    var gatherOpenPaEncoderValues = false
        private set
    var openPaValue: Int? = null
        private set

    var gatheredOpenPaValues = mutableListOf<Int>()
        private set
    var openPaValueFound: Boolean = false
        private set


    var gatherOpenFaEncoderValues = false
        private set
    var openFaValue: Int? = null
        private set

    var gatheredOpenFaValues = mutableListOf<Int>()
        private set
    var openFaValueFound: Boolean = false
        private set

    var validRangeMap: Boolean = false
        private set

    private var lastlogged = 0L
    var currentPosition: Int = -1
        private set
    var unadjusted: Int = -1
        private set

    lateinit var encoderValuesAndRanges: EncoderValuesAndRangesHolder
        private set

    var readyToAdjust = false

    val workerThread: HandlerThread
    val workerHandler: Handler
    private var spiBus: SpiDevice? = null
    private lateinit var spiPoller: Runnable

    private var isSpiBusInitialized = false

    private val encoderMonitor: EncoderMonitor

    private lateinit var linkedRangeMapNonAdjusted: LinkedHashMap<BarrierState, List<IntProgression>>
    private lateinit var linkedRangeMapAdjusted: LinkedHashMap<BarrierState, List<IntProgression>>
    var speedPositionArray = IntArray(3)
    var speedDivider: Int = 0
    var speed: Int = 0
    var acc: Int = 0
//    var write_divider: Int = 0
//    var positionLog: String = ""
//    val filename = "position_log.txt"
//    var path = Environment.getExternalStorageDirectory()
//    var fileOut = File(path, filename)

    init {
//        fileOut.createNewFile()
        speedPositionArray[0] = 0
        speedPositionArray[1] = 0
        speedPositionArray[2] = 0
        logger.log(TAG, "Creating SPI Encoder")

        workerThread = HandlerThread("${TAG}Thread")
        workerThread.start()
        workerHandler = Handler(workerThread.looper)

        val manager = PeripheralManager.getInstance()
        val deviceList = manager.spiBusList

        if (deviceList.isEmpty()) {
            logger.log(TAG, "No SPI bus available")
        } else {
            logger.log(TAG, "List of SPI devices: $deviceList")
        }

        if(!debugMode) {
            spiBus = manager.openSpiDevice(spiDeviceName)
            spiBus?.apply {
                setMode(SpiDevice.MODE2)
                setFrequency(FREQUENCY_HZ)
                setBitsPerWord(BITS_PER_WORD)
                setDelay(DELAY_Us)
                isSpiBusInitialized = true
                logger.log(TAG, "$spiDeviceName bus initialized")

                // correct way to do repeating tasks in Android
                spiPoller = Runnable {
                    val data = ByteArray(2)
                    spiBus?.let {
                        try {
                            sendCommand(it, data)
                        } catch (e: IOException) {
                            logger.log(TAG, "Read error: ${getStackTraceString(e)}")
                        }
                    }
                    workerHandler.postDelayed(
                            spiPoller,
                            POLL_TIME_MS
                    )
                }
                workerHandler.postDelayed(
                        spiPoller,
                        1000L
                )
            }

        }
        encoderMonitor = EncoderMonitor(this)
    }

    @Throws(IOException::class)
    private fun sendCommand(device: SpiDevice, buffer: ByteArray) {
        device.apply {
            ByteArray(buffer.size).also { response ->
                device.read(response, buffer.size)
                processResponse(response)
            }
        }
    }

    // info from SR-12 data sheet
    private fun processResponse(bytes: ByteArray) {
        val now = System.currentTimeMillis()
        // don't process all zero byte array
        if ((bytes[0] and 64).toInt() != 0) {

            var gray2Bin = grayCodeToBin(findGrayCode(bytes))
            speedDivider += 1
//            write_divider += 1
            speedDivider = speedDivider % 10
            if (speedDivider == 0) {
                speedPositionArray[2] = speedPositionArray[1]
                speedPositionArray[1] = speedPositionArray[0]
                speedPositionArray[0] = gray2Bin
                speed = speedPositionArray[0] - speedPositionArray[1]
                acc = speedPositionArray[2] + speedPositionArray[0] - 2 * speedPositionArray[1]
//                positionLog = positionLog + positionArray[0].toString() + "," + positionArray[1].toString() + ", " + abs(positionArray[0] - positionArray[1]).toString() + "\r\n"
 //               logger.log(TAG, "!!!!!!!!!! gray2bin = " + gray2Bin.toString() + /*", position[0] = " + positionArray[0] + ", position[1] = " + positionArray[1] + */", difference = " + abs(positionArray[0] - positionArray[1]).toString())

            }
/*            write_divider = write_divider % 100
            if (write_divider == 0) {
                fileOut.appendText(positionLog)
                positionLog = ""
            }
            */

            unadjusted = gray2Bin
            if(readyToAdjust){
                gray2Bin = encoderValuesAndRanges.translateNonAdjustedToAdjusted(gray2Bin)
            }

            if (currentPosition != gray2Bin || gray2Bin != 1024 ) {
                currentPosition = gray2Bin

                if("LocalBarrier" == ownerName){
                    logger.updateLocalEncoder(unadjusted,currentPosition)
                }
/*                else if ("RemoteBarrier" == ownerName){
                    logger.updateRemoteEncoder(unadjusted,currentPosition)
                }*/
               // logger.log(TAG, "Encoder value : $currentPosition")
/*                if (now - lastlogged >= 5000) {
                    logger.log(TAG, "Encoder value nonadjusted:$unadjusted adjusted: $currentPosition")
                    lastlogged = now
                }*/
            }
            else{
                logger.log(TAG, "Encoder got bad value from  adjustment: $gray2Bin from $unadjusted")
            }

            // If set, we gather the values and take the one with the highest count
            if (gatherCenterEncoderValues) {
                gatheredCenterValues.add(currentPosition)
            }
            if (gatherOpenPaEncoderValues) {
                gatheredOpenPaValues.add(currentPosition)
            }
            if (gatherOpenFaEncoderValues) {
                gatheredOpenFaValues.add(currentPosition)
            }

        }
    }

    fun gatherCenterValues() {
        if (centerValueFound) {
            return
        }
        workerHandler.post {
            gatherCenterEncoderValues = true
        }
        workerHandler.postDelayed({
            gatherCenterEncoderValues = false
            val maxCount = gatheredCenterValues.groupingBy { it }
                .eachCount().maxBy { it.value }

            val mostRepeated = maxCount?.key
            val repeatCount = maxCount?.value

            if (mostRepeated != null || repeatCount != null) {
                logger.log(
                    TAG,
                    "Center being set to $mostRepeated had $repeatCount during initial config"
                )
                centerValue = mostRepeated

                val encoderValues = EncoderValues.create(openFaValue!!, centerValue!!,openPaValue!! )
                val nonAdjusted = calculateEncoderValuesAndRanges(encoderValues)
                val adjusted = adjustEncoderValues(nonAdjusted)
                encoderValuesAndRanges =  EncoderValuesAndRangesHolder(nonAdjusted,adjusted)

                linkedRangeMapNonAdjusted = linkedMapOf(
                        BarrierState.OPEN_FA            to encoderValuesAndRanges.nonAdjusted.openFa.ranges,
                        BarrierState.BETWEEN_FA_CENTER  to encoderValuesAndRanges.nonAdjusted.betweenFaCenter.ranges,
                        BarrierState.CENTER             to encoderValuesAndRanges.nonAdjusted.center.ranges,
                        BarrierState.BETWEEN_PA_CENTER  to encoderValuesAndRanges.nonAdjusted.betweenPaCenter.ranges,
                        BarrierState.OPEN_PA            to encoderValuesAndRanges.nonAdjusted.openPa.ranges
                )

                linkedRangeMapAdjusted = linkedMapOf(
                        BarrierState.OPEN_FA            to encoderValuesAndRanges.adjusted.openFa.ranges,
                        BarrierState.BETWEEN_FA_CENTER  to encoderValuesAndRanges.adjusted.betweenFaCenter.ranges,
                        BarrierState.CENTER             to encoderValuesAndRanges.adjusted.center.ranges,
                        BarrierState.BETWEEN_PA_CENTER  to encoderValuesAndRanges.adjusted.betweenPaCenter.ranges,
                        BarrierState.OPEN_PA            to encoderValuesAndRanges.adjusted.openPa.ranges
                )

                val error = checkForError(encoderValuesAndRanges)
                if(error.first){
                    logger.log(TAG, "ERROR - ${error.second}")
                    logger.log(TAG, "ERROR values: $encoderValuesAndRanges")
                }
                else{
                    centerValueFound = true
                    validRangeMap = true
                    readyToAdjust = true
                    logger.log(TAG, "NON ADJUSTED - ${linkedRangeMapNonAdjusted.pTS()}")
                    logger.log(TAG, "ADJUSTED     - ${linkedRangeMapAdjusted.pTS()}")
                }
            }
            if (!centerValueFound) {
                logger.log(TAG, "ERROR: Could not determine CENTER POS")
                centerValueFound = false
            }

        }, CALC_RANGE_TIME)
    }

    fun gatherOpenPAValues() {
        if (openPaValueFound) {
            return
        }
        workerHandler.post {
            gatherOpenPaEncoderValues = true
        }
        workerHandler.postDelayed({
            gatherOpenPaEncoderValues = false
            val maxCount = gatheredOpenPaValues.groupingBy { it }
                .eachCount().maxBy { it.value }

            val mostRepeated = maxCount?.key
            val repeatCount = maxCount?.value

            if (mostRepeated != null || repeatCount != null) {
                logger.log(
                    TAG,
                    "OpenPA being set to $mostRepeated had $repeatCount during initial config"
                )
                openPaValue = mostRepeated
                openPaValueFound = true
            }
            if (!openPaValueFound) {
                logger.log(TAG, "ERROR: Could not determine OPEN PA POS")
            }

        }, CALC_RANGE_TIME)
    }

    fun gatherOpenFAValues() {
        if (openFaValueFound) {
            return
        }
        workerHandler.post {
            gatherOpenFaEncoderValues = true
        }
        workerHandler.postDelayed({
            gatherOpenFaEncoderValues = false
            val maxCount = gatheredOpenFaValues.groupingBy { it }
                .eachCount().maxBy { it.value }

            val mostRepeated = maxCount?.key
            val repeats = maxCount?.value

            if (mostRepeated != null || repeats != null) {
                logger.log(
                    TAG,
                    "OpenFA being set to $mostRepeated had $repeats during initial config"
                )
                openFaValue = mostRepeated
                openFaValueFound = true
            }
            if (!openFaValueFound) {
                logger.log(TAG, "ERROR: Could not determine OPEN FA POS")
            }

        }, CALC_RANGE_TIME)
    }

    fun isEncoderConfigurationComplete(): Boolean {
        return centerValueFound && openPaValueFound && openFaValueFound && validRangeMap && ::linkedRangeMapAdjusted.isInitialized
    }

    fun getEncoderBarrierState(): BarrierState {
        return encoderMonitor.barrierState
    }

    fun isEncoderOpenFa(): Boolean {
        return BarrierState.OPEN_FA == getEncoderBarrierState()
    }

    fun isEncoderOpenTowardsFa(): Boolean {
        return BarrierState.OPEN_FA == getEncoderBarrierState() || BarrierState.BETWEEN_FA_CENTER == getEncoderBarrierState()
    }

    fun isEncoderOpenTowardsPa(): Boolean {
        return BarrierState.OPEN_PA == getEncoderBarrierState() || BarrierState.BETWEEN_PA_CENTER == getEncoderBarrierState()
    }

    fun isEncoderOpenPa(): Boolean {
        return BarrierState.OPEN_PA == getEncoderBarrierState()
    }

    fun isEncoderCenter(): Boolean {
        return BarrierState.CENTER == getEncoderBarrierState()
    }

    fun isEncoderBetweenPaCenter():Boolean {
        return BarrierState.BETWEEN_PA_CENTER == getEncoderBarrierState()
    }
    fun isEncoderBetweenFaCenter():Boolean {
        return BarrierState.BETWEEN_FA_CENTER == getEncoderBarrierState()
    }

    fun getEncoderRanges(): LinkedHashMap<BarrierState, List<IntProgression>> {
        return if (!::linkedRangeMapAdjusted.isInitialized) linkedMapOf<BarrierState, List<IntProgression>>() else linkedRangeMapAdjusted
    }

    fun getOpenPaRange():List<IntProgression>{
        return linkedRangeMapAdjusted.get(BarrierState.OPEN_PA)!!
    }

    fun getOpenFaRange():List<IntProgression>{
        return linkedRangeMapAdjusted.get(BarrierState.OPEN_FA)!!
    }

    fun getCenterRange():List<IntProgression>{
        return linkedRangeMapAdjusted.get(BarrierState.CENTER)!!
    }

    override fun onDestroy() {
        spiBus?.close()
        workerThread.quitSafely()
    }

    companion object {
        private const val CALC_RANGE_TIME = 5000L
        private const val POLL_TIME_MS = 1L

        private const val FREQUENCY_HZ = 2_000
        private const val BITS_PER_WORD = 8
        private const val DELAY_Us = 4

        const val minRes = 0
        const val maxRes = 511
        const val slipRange = 5
        const val centerRange = 10 // this value x2 is the valid range
        const val openRange = 10 // this value x2 is the valid range
        const val adjustedCenter = 250
        val resolution = minRes..maxRes
    }
}
