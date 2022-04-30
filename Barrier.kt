package gov.bart.fce.bas.barrier

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import gov.bart.fce.bas.BasLogger
import gov.bart.fce.bas.LifeCycleAwareClass
import gov.bart.fce.bas.encoder.Encoder
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*

abstract class Barrier(ownerName: String) : LifeCycleAwareClass {

    val logger = BasLogger.getInstance()
    var barrierInitialized: Boolean = false

    val transitionTime = 8_000L
    val TAG = ownerName

    var workerThread = HandlerThread("${TAG}Thread")
    var workerHandler: Handler

    var currentState: BarrierState = BarrierState.UNKNOWN
        private set
    private var previousState: BarrierState = BarrierState.UNKNOWN

    var openCalled = false
    var closeCalled = false
    var forceTransition = false
    var forceTransitionCount = 0
    var forceTransitionCountMax = 100  // Right now poll time is 5 ms so max will be reached in 500ms

    var openBarrierPaRunnable: Runnable? = null
    var openBarrierFaRunnable: Runnable? = null
    var closeBarrierRunnable: Runnable? = null

    var ensureBarrierCloses: Runnable? = null

    var openBarrierPaRunnableNormalize: Runnable? = null
    var openBarrierFaRunnableNormalize: Runnable? = null

    var step1 = false
    var normalizeLoopCount = 0
    var normalizeCenterStepOne: Runnable? = null
    var normalizeCenterStepTwo: Runnable? = null
    var normalizeCenterStep3: Runnable? = null
    var normalizeCenterStep4: Runnable? = null

    val barrierStatePollTime = 5L

    val baseFrequency = 200.0
    var baseDutyCycleMultiplier = 1.0
    val baseDutyCycle
        get() = 26.0 * baseDutyCycleMultiplier
    val configurationDutyCycle = 30.0

    var lockEngagedCount = 0
    val MAX_COUNT = 10

    val PWM_MAX = 100.0
    val PWM_HALF = 50.0
    val PWM_MIN = 0.0
    var PWM_DUTY_CYCLE = 100.0

    var barrierStartPosition = 0.0
    var currentPosition = 0.0
    var previousPosition = 0.0
    var lastPositionChange = 0L

    var startTime: Date = Date()

    val velocityFactorOpenFa = VelocityFactorQueue("velocityFactorOpenFa",100)
    val velocityFactorOpenFaToCenter = VelocityFactorQueue("velocityFactorOpenFaToCenter",100)
    val velocityFactorOpenPa = VelocityFactorQueue("velocityFactorOpenPa",100)
    val velocityFactorOpenPaToCenter = VelocityFactorQueue("velocityFactorOpenPaToCenter",100)

//  AFG

    val FA2CenterVelocityLevel = 2000//5000
    val Center2PAVelocityLevel = 1000//3500
    val PA2CenterVelocityLevel = 1500//3500
    val Center2FAVelocityLevel = 2000//5000
    val FA2CenterUninitializedBrakingEncoderVal = 200
    val Center2PAUninitializedBrakingEncoderVal = 310
    val PA2CenterUninitializedBrakingEncoderVal = 290
    val Center2FAUninitializedBrakingEncoderVal = 165
    var FA2CenterInitializedBrakingEncoderVal = 0//195
    var Center2PAInitializedBrakingEncoderVal = 0//315
    var PA2CenterInitializedBrakingEncoderVal = 0//325
    var Center2FAInitializedBrakingEncoderVal = 0//175

    // Reversible
/*
    val FA2CenterVelocityLevel = 40000
    val Center2PAVelocityLevel = 40000
    val PA2CenterVelocityLevel = 50000
    val Center2FAVelocityLevel = 65000
    val FA2CenterUninitializedBrakingEncoderVal = 200
    val Center2PAUninitializedBrakingEncoderVal = 310
    val PA2CenterUninitializedBrakingEncoderVal = 290
    val Center2FAUninitializedBrakingEncoderVal = 170
    val FA2CenterInitializedBrakingEncoderVal = 210
    val Center2PAInitializedBrakingEncoderVal = 315
    val PA2CenterInitializedBrakingEncoderVal = 325
    val Center2FAInitializedBrakingEncoderVal = 190
*/
    var swingStartTime: Date = Date()
    var swingEndTime: Date = Date()

    val MIN_CHANGE_VALUE = 5.0
    val MIN_CHANGE_TIME = 500L

    var retractionStarted = false

    var closeDir = CloseDir.UNKNOWN

    init {
        readBrakingValues()
        workerThread.start()
        workerHandler = Handler(workerThread.looper)
    }


    abstract fun closeBarrier()
    abstract fun openBarrierPa()
    abstract fun openBarrierFa()
    abstract fun extendDbLock()
    abstract fun extendLeafLock()
    abstract fun retractLeafLock()
    abstract fun retractDbLock()
    abstract fun isBarrierClosedAndLocked(): Boolean
    abstract fun isBarrierFullyOpened(): Boolean
    abstract fun getEncoder(): Encoder
    abstract fun isInitialized(): Boolean
    abstract fun isCenterFound(): Boolean
    abstract fun isOpenPaFound(): Boolean
    abstract fun isOpenFaFound(): Boolean
    abstract fun getEncoderRanges(): String
    abstract fun setGpioLow()
    abstract fun enablePWM()
    abstract fun disablePWM()
    abstract fun setPWMToZero()

    fun getState(): BarrierState {
        return currentState
    }

    fun setState(state: BarrierState) {
        previousState = currentState
        currentState = state
        if (previousState != currentState) {
            logger.log(TAG, "state now $currentState encoder value:[${getEncoder().currentPosition}]")
        }
    }

    fun sleepTime(millis: Long): Boolean {
        try {
            Thread.sleep(millis)
        } catch (e: Exception) {
            logger.log(TAG, "transition interrupted! ")
            return false
        }
        return true
    }

    fun attemptStateChange(runnable: Runnable,delay: Long = 2L) {
        workerHandler.removeCallbacks(runnable)
        workerHandler.postDelayed(runnable, delay)
    }

    fun startForceBarrierTransitionInterrupt(){
        forceTransition = true
        forceTransitionCount = 0
    }

    fun stopForceBarrierTransitionInterrupt(){
        forceTransition = false
        forceTransitionCount=0
    }

    fun checkForceBarrierTransitionComplete(){
        if(forceTransition && forceTransitionCount <=forceTransitionCountMax){
            forceTransitionCount += 1
        }
        else{
            forceTransition = false
            forceTransitionCount = 0
        }
    }

    fun readBrakingValues() {
        val lineList = mutableListOf<String>()
        try {
            File("/sdcard/Download/brakeValues.json").useLines { lines ->
                lines.forEach {
                    lineList.add(
                        it
                    )
                }
            }
            lineList.forEach {
//            logger.log(TAG,"###############################brakeList = " + it)
                val valueList = JSONObject(it)
                FA2CenterInitializedBrakingEncoderVal =
                    valueList.getInt("FA2CenterInitializedBrakingEncoderVal")
                Center2PAInitializedBrakingEncoderVal =
                    valueList.getInt("Center2PAInitializedBrakingEncoderVal")
                PA2CenterInitializedBrakingEncoderVal =
                    valueList.getInt("PA2CenterInitializedBrakingEncoderVal")
                Center2FAInitializedBrakingEncoderVal =
                    valueList.getInt("Center2FAInitializedBrakingEncoderVal")
                logger.log(
                    TAG,
                    "***************    Center2FAInitializedBrakingEncoderVal value = " + Center2FAInitializedBrakingEncoderVal.toString()
                )

            }
6
        } catch (fnfe: FileNotFoundException) {
            Log.e(TAG, "File not found")
            FA2CenterInitializedBrakingEncoderVal = 205  //AFG 195 RFG 205
            Center2PAInitializedBrakingEncoderVal = 315
            PA2CenterInitializedBrakingEncoderVal = 342  // AFG 342 RFG 342 AFG was 325
            Center2FAInitializedBrakingEncoderVal = 185  //AFG 175 RFG 185
        } catch (ioe: IOException) {
            Log.e(TAG, "IOException reading brake value file")
        }

    }


}


fun isErrorState(state: BarrierState): Boolean {
    for (s in BarrierState.values()) {
        when (state) {
            BarrierState.ERROR_CENTER, BarrierState.ERROR_OPEN_FA, BarrierState.ERROR_OPEN_PA,
            BarrierState.ERROR_BETWEEN_PA_CENTER, BarrierState.ERROR_BETWEEN_FA_CENTER -> {
                return true
            }
        }
    }
    return false
}

enum class BarrierState {
    CENTER,
    OPEN_FA,
    OPEN_PA,
    BETWEEN_PA_CENTER,
    BETWEEN_FA_CENTER,
    ERROR_CENTER,
    ERROR_OPEN_FA,
    ERROR_OPEN_PA,
    ERROR_BETWEEN_PA_CENTER,
    ERROR_BETWEEN_FA_CENTER,
    UNKNOWN
}

enum class CloseDir {
    PA_TO_CENTER,
    FA_TO_CENTER,
    CENTERED,
    UNKNOWN
}




