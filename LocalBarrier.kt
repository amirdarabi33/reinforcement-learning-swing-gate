package gov.bart.fce.bas.barrier

import android.os.CountDownTimer
import android.util.Log
import com.google.android.things.pio.Gpio
import com.google.android.things.pio.PeripheralManager
import gov.bart.fce.bas.*
import gov.bart.fce.bas.encoder.Encoder
import gov.bart.fce.bas.encoder.pTS
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.pow

class  LocalBarrier() :
    Barrier("LocalBarrier") {


    private var encoder: Encoder = Encoder(TAG, BARLOCAL_SPI_ENC)

    val A1: GpioContainer =
        buildLifeCycleAwareGpio(
            BCM21,
            Gpio.DIRECTION_OUT_INITIALLY_HIGH,
            -1,
           activeType =  Gpio.ACTIVE_LOW
        )
    val A2: GpioContainer =
        buildLifeCycleAwareGpio(
            BCM20,
            Gpio.DIRECTION_OUT_INITIALLY_HIGH,
            -1,
            activeType =  Gpio.ACTIVE_LOW
        )
    val B1: GpioContainer =
        buildLifeCycleAwareGpio(
            BCM26,
            Gpio.DIRECTION_OUT_INITIALLY_HIGH,
            -1,
            activeType =  Gpio.ACTIVE_LOW
        )

    val DBLOCK = buildLifeCycleAwareGpio(BCM16,
        Gpio.DIRECTION_OUT_INITIALLY_HIGH,
        -1,
        activeType =  Gpio.ACTIVE_LOW
    )

    val LEAFLOCK = buildLifeCycleAwareGpio(BCM19,
        Gpio.DIRECTION_OUT_INITIALLY_HIGH,
        -1,
        activeType =  Gpio.ACTIVE_LOW
    )

    val PWM = PeripheralManager.getInstance().openPwm("PWM0")

    var ignoreBraking: Boolean = false
    var th_d_pc = 0
    var th_dd_pc = 0
    var th_d_cp = 0
    var th_dd_cp = 0
    var th_d_cf = 0
    var th_dd_cf = 0
    var th_max_pc = 0
    var th_d_fc = 0
    var th_dd_fc = 0
    var th_max_fc = 0
    var ignore_reward: Boolean= true
    init {
        logger.log(TAG, "$TAG created")
        PWM.setPwmFrequencyHz(baseFrequency)
        PWM.setPwmDutyCycle(configurationDutyCycle)

        ensureBarrierCloses = Runnable {
            logger.log(TAG,"ensureBarrierCloses time limit passed, sending close command")
            BarrierAisleSystem.getInstance().closeBarriersZB0()
        }


        normalizeCenterStepOne = Runnable {
            logger.log(TAG, "normalizeCenterStepOne called")
            ignoreBraking = true
            if(encoder.isEncoderCenter() && !forceTransition) {
                closeDir =CloseDir.CENTERED
                PWM.setPwmDutyCycle(PWM_MAX/*40.0*/)
                A1.gpio.value = false
                B1.gpio.value = false
                A2.gpio.value = true
                normalizeCenterStepTwo?.let { attemptStateChange(it,/* AFG200L*/150L) }
                step1 = false
            }
            else{
                step1 = false
                logger.log(TAG, "", forceTransition_step1_else = " + forceTransition.toString()")
                PWM.setPwmDutyCycle(PWM_MIN)
                setGpioLow()
                closeBarrierRunnable?.let { attemptStateChange(it,100L) }
            }
        }

        normalizeCenterStepTwo = Runnable {
            logger.log(TAG, "normalizeCenterStepTwo called")
            if(encoder.isEncoderCenter() && !forceTransition) {
                //PWM.setPwmDutyCycle(PWM_HALF)
                A1.gpio.value = false
                B1.gpio.value = true
                A2.gpio.value = true
                swingStartTime = Date()
                //logger.log(TAG, "Speed = " + encoder.speed.toString())
                if (encoder.speed <= 0) {
                    swingEndTime = Date()
//                    val sdf: SimpleDateFormat = SimpleDateFormat("MM/dd/yyyy HH:mm.ss.SSS")

                    //logger.log(
                    //    TAG,
                    //    "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@start time = " + sdf.format(
                    //        swingStartTime
                    //    ) + ", end time = " + sdf.format(swingEndTime) + ", difference = " + (swingEndTime.time - swingStartTime.time).toString() + " seconds"
                    //)
                }
                normalizeCenterStep3?.let { attemptStateChange(it,/* AFG200L*/150L) }
            }
            else{
                PWM.setPwmDutyCycle(PWM_MIN)
                logger.log(TAG, "", forceTransition_step2_else = " + forceTransition.toString()")
                setGpioLow()
                closeBarrierRunnable?.let { attemptStateChange(it,100L) }
            }
        }

        normalizeCenterStep3 = Runnable {
            logger.log(TAG, "normalizeCenterStep3 called")
            if(encoder.isEncoderCenter() && !forceTransition) {
                A1.gpio.value = false
                B1.gpio.value = false
                A2.gpio.value = true
                normalizeCenterStep4?.let { attemptStateChange(it,/* AFG200L*/150L) }
            }
            else{
                PWM.setPwmDutyCycle(PWM_MIN)
                setGpioLow()
                logger.log(TAG, "", forceTransition_step3_else = " + forceTransition.toString()")
                closeBarrierRunnable?.let { attemptStateChange(it,100L) }
            }
        }

        normalizeCenterStep4 = Runnable {
            //logger.log(TAG, "normalizeCenterStep4 called")
            logger.log(TAG, "normalizeCenterStep4 encoder.isEncoderCenter() = " + encoder.isEncoderCenter().toString() + ", forceTransition = " + forceTransition.toString())
            if(encoder.isEncoderCenter() && !forceTransition) {
                A1.gpio.value = false
                B1.gpio.value = true
                A2.gpio.value = true
                if ((encoder.currentPosition in 249..251/*AFG == 250*/) || (normalizeLoopCount >= 5)) {
                    closeBarrierRunnable?.let { attemptStateChange(it, 300L) }
                }
                else {
                    normalizeLoopCount += 1
                    normalizeCenterStepOne?.let { attemptStateChange(it, /* AFG200L*/150L) }
                }
            }
            else{
                PWM.setPwmDutyCycle(PWM_MIN)
                setGpioLow()
                logger.log(TAG, "", forceTransition_step4_else = " + forceTransition.toString()")
                closeBarrierRunnable?.let { attemptStateChange(it,100L) }
            }
        }

        openBarrierPaRunnableNormalize = Runnable {
            //logger.log(TAG,"LOCAL openBarrierPaRunnableNormalize")
            //logger.log(TAG, "openBarrierPaRunnableNormalize called")
            retractDbLock()
            retractLeafLock()
            sleepTime(100)
            /*
            PWM.setPwmDutyCycle(5.0)
            A1.gpio.value = true
            sleepTime(250)
            A1.gpio.value = false
            B1.gpio.value = true
            sleepTime(250)
            PWM.setPwmDutyCycle(baseDutyCycle)
            A1.gpio.value = false
            B1.gpio.value = true
            A2.gpio.value = false

             */
            openBarrierPaRunnable?.let { attemptStateChange(it, 10L) }
        }

        openBarrierFaRunnableNormalize = Runnable {
            //logger.log(TAG,"LOCAL openBarrierFaRunnableNormalize")
            //logger.log(TAG, "Local openBarrierFaRunnableNormalize called")
            retractDbLock()
            retractLeafLock()
            sleepTime(100)
 /*           PWM.setPwmDutyCycle(5.0)
            B1.gpio.value = true
            sleepTime(250)
            A1.gpio.value = true
            B1.gpio.value = false
            sleepTime(250)
            PWM.setPwmDutyCycle(baseDutyCycle)
            A1.gpio.value = true
            B1.gpio.value = false
            A2.gpio.value = false

  */
            openBarrierFaRunnable?.let { attemptStateChange(it, 10L) }
        }

        openBarrierFaRunnable = Runnable {
            //logger.log(TAG, "openBarrierFaRunnable called")
            step1 = false
            if(DBLOCK.gpio.value){
                if(!retractionStarted){
                    retractionStarted = true
                    openBarrierFaRunnableNormalize?.let { attemptStateChange(it, 5L) }
                }
                return@Runnable
            }
            if (closeCalled) {
                logger.log(TAG,"Close called during open, stopping open and closing")
                setGpioLow()
                barrierStartPosition = 0.0
                startForceBarrierTransitionInterrupt()
                return@Runnable
            }
            if ((encoder.currentPosition > 130) && (barrierInitialized)){
                th_d_cf = encoder.speed
                th_dd_cf = encoder.acc
            }
            if (encoder.openFaValueFound ) {
                if(openCalled){
                    velocityFactorOpenFa.start()
                }
                if (encoder.isEncoderOpenFa() && !forceTransition) {
                    openCalled = false
                    velocityFactorOpenFa.stop()
                    barrierStartPosition = 0.0
                    stopForceBarrierTransitionInterrupt()

                    setState(encoder.getEncoderBarrierState())
                    PWM.setPwmDutyCycle(baseDutyCycle)
                    B1.gpio.value = false
                    A2.gpio.value = false
                    A1.gpio.value = true
                    ensureBarrierCloses?.let{ attemptStateChange(it,60_000L)}
                } else {
                    checkForceBarrierTransitionComplete()
                    var currentEncoderValue = (encoder.currentPosition).toDouble()
                    var endPoint = (encoder.getOpenFaRange()[0].first).toDouble()
                    var newDutyCycle = calculateDuty(currentEncoderValue,currentEncoderValue,endPoint,velocityFactorOpenFa,Center2FAVelocityLevel)

                    if ((encoder.currentPosition < Center2FAInitializedBrakingEncoderVal) && (barrierInitialized) && (encoder.speed <= -3) && (!ignoreBraking)/* || (encoder.speed < 2)*/) {
                        A1.gpio.value = true
                        A2.gpio.value = false
                        B1.gpio.value = true
                        var limited_newDutyCycle = newDutyCycle * 5//10
                        if (limited_newDutyCycle > 100)
                            limited_newDutyCycle = 100.0
                        PWM.setPwmDutyCycle(limited_newDutyCycle)
                    }
                    else if ((encoder.currentPosition < Center2FAUninitializedBrakingEncoderVal) && (!barrierInitialized) && (encoder.speed <= -3)/* || (encoder.speed < 2)*/) {
                        A1.gpio.value = true
                        A2.gpio.value = false
                        B1.gpio.value = true
                        var limited_newDutyCycle = newDutyCycle * 5//10
                        if (limited_newDutyCycle > 100)
                            limited_newDutyCycle = 100.0
                        PWM.setPwmDutyCycle(limited_newDutyCycle)
                    }
                    else {

                        PWM.setPwmDutyCycle(newDutyCycle)
                        A1.gpio.value = true
                        A2.gpio.value = false
                        B1.gpio.value = false
                    }
                    setState(encoder.getEncoderBarrierState())

                    ensureBarrierCloses?.let {  workerHandler.removeCallbacks(it) }
                    openBarrierFaRunnable?.let { attemptStateChange(it, barrierStatePollTime) }
                }
            } else {
                logger.log(TAG, "openBarrierFa called and encoder is NOT configured, using timers")
                B1.gpio.value=true
                sleepTime(500)
                B1.gpio.value=false
                A1.gpio.value = true
                setState(BarrierState.BETWEEN_FA_CENTER)
                sleepTime(transitionTime)
                setState(BarrierState.OPEN_FA)
                openCalled = false
                setGpioLow()
            }
        }

        openBarrierPaRunnable = Runnable {
            //logger.log(TAG, "openBarrierPaRunnable called")
            step1 = false
            if(DBLOCK.gpio.value){
                if(!retractionStarted){
                    retractionStarted = true
                    openBarrierPaRunnableNormalize?.let { attemptStateChange(it, 5L) }
                }
                return@Runnable
            }
            if (closeCalled) {
                logger.log(TAG,"Close called during open, stopping open and closing")
                setGpioLow()
                barrierStartPosition = 0.0
                startForceBarrierTransitionInterrupt()
                return@Runnable
            }
            if ((encoder.currentPosition < 360) && (barrierInitialized)){
                th_d_cp = encoder.speed
                th_dd_cp = encoder.acc
            }

            if (encoder.openPaValueFound) {
                if(openCalled){
                    velocityFactorOpenPa.start()
                }
                if (encoder.isEncoderOpenPa()  && !forceTransition) {
                    openCalled = false
                    velocityFactorOpenPa.stop()
                    barrierStartPosition = 0.0
                    stopForceBarrierTransitionInterrupt()
                    setState(encoder.getEncoderBarrierState())
                        A1.gpio.value = false
                        A2.gpio.value = false
                        B1.gpio.value = true
                        PWM.setPwmDutyCycle(baseDutyCycle)
                    ensureBarrierCloses?.let{ attemptStateChange(it,60_000L)}
                } else {
                    checkForceBarrierTransitionComplete()
                    val currentEncoderValue = (encoder.currentPosition).toDouble()
                    val endPoint = (encoder.getOpenPaRange()[0].last).toDouble()
                    val newDutyCycle = calculateDuty(currentEncoderValue,currentEncoderValue,endPoint,velocityFactorOpenPa,Center2PAVelocityLevel)

                    if ((encoder.currentPosition > Center2PAInitializedBrakingEncoderVal) && (barrierInitialized) && (encoder.speed >= 3) && (!ignoreBraking)/* || (encoder.speed < 2)*/) {
                        A1.gpio.value = false
                        A2.gpio.value = true
                        B1.gpio.value = true
                        var limited_newDutyCycle = newDutyCycle * 5//10
                        if (limited_newDutyCycle > 100)
                            limited_newDutyCycle = 100.0
                        PWM.setPwmDutyCycle(limited_newDutyCycle)
                    }
                    else if ((encoder.currentPosition > Center2PAUninitializedBrakingEncoderVal) && (!barrierInitialized) && (encoder.speed >= 3)/* || (encoder.speed < 2)*/) {
                        A1.gpio.value = false
                        A2.gpio.value = true
                        B1.gpio.value = true
                        var limited_newDutyCycle = newDutyCycle * 5//10
                        if (limited_newDutyCycle > 100)
                            limited_newDutyCycle = 100.0
                        PWM.setPwmDutyCycle(limited_newDutyCycle)
                    }
                    else
                    {

                        PWM.setPwmDutyCycle(newDutyCycle)
                        A1.gpio.value = false
                        A2.gpio.value = false
                        B1.gpio.value = true
                    }
                   setState(encoder.getEncoderBarrierState())
                   ensureBarrierCloses?.let {  workerHandler.removeCallbacks(it) }
                   openBarrierPaRunnable?.let { attemptStateChange(it,barrierStatePollTime) }
               }
           } else {
               logger.log(TAG, "openBarrierPa called and encoder is NOT configured, using timers")
               B1.gpio.value = true
               setState(BarrierState.BETWEEN_PA_CENTER)
               sleepTime(transitionTime)
               setState(BarrierState.OPEN_PA)
               openCalled = false
               setGpioLow()
           }
       }
        closeBarrierRunnable = Runnable {
            var closeFromFAIntention: Int = 0
            if (openCalled) {
                logger.log(TAG,"Open called during close, stop close process")
                setGpioLow()
                barrierStartPosition = 0.0
                startForceBarrierTransitionInterrupt()
                return@Runnable
            }
            if (encoder.centerValueFound) {
                if(closeCalled){
                    if(CloseDir.FA_TO_CENTER == closeDir){
                        closeFromFAIntention = 1
                        logger.log(TAG, "closeFromFAIntention = 1")
                        unknown++
                        velocityFactorOpenFaToCenter.start()
                    }
                    if(CloseDir.PA_TO_CENTER == closeDir){
                        closeFromFAIntention = 2
                        logger.log(TAG, "closeFromFAIntention = 2")
                        unknown++
                        velocityFactorOpenPaToCenter.start()
                    }
                }
                if (encoder.isEncoderCenter() && !forceTransition) {
                    stopForceBarrierTransitionInterrupt()
                    closeCalled = false
                    if ((CloseDir.FA_TO_CENTER == closeDir) && (closeFromFAIntention == 1)){
                        velocityFactorOpenFaToCenter.stop()

                    }
                    if ((CloseDir.PA_TO_CENTER == closeDir) && (closeFromFAIntention == 2)){
                        velocityFactorOpenPaToCenter.stop()

                    }
                    if ((encoder.currentPosition < 250) && (CloseDir.FA_TO_CENTER == closeDir) && (ignore_reward) && (barrierInitialized) ) {
                            th_d_fc = encoder.speed
                            th_dd_fc = encoder.acc
                        }
                    if ((encoder.currentPosition > 250) && (CloseDir.FA_TO_CENTER == closeDir) && (ignore_reward) && (barrierInitialized)) {
                            th_max_fc = encoder.currentPosition
                    }
                    
                    if ((encoder.currentPosition > 250) && (CloseDir.PA_TO_CENTER == closeDir) && (ignore_reward) && (barrierInitialized)) {
                            th_d_pc = encoder.speed
                            th_dd_pc = encoder.acc
                        }
                    if ((encoder.currentPosition < 250) && (CloseDir.PA_TO_CENTER == closeDir) && (ignore_reward) && (barrierInitialized)) {
                            th_max_pc = encoder.currentPosition
                    }
                    barrierStartPosition = 0.0

                    if(!DBLOCK.gpio.value){
                        extendDbLock()
                    }
                    setState(encoder.getEncoderBarrierState())

                    if(closeDir != CloseDir.CENTERED && DBLOCK.gpio.value){
                        logger.log(TAG, "step1 value = " + step1.toString())
                        if(!step1){
                            step1 = true
                            ignore_reward= false
                            normalizeLoopCount = 0
                            normalizeCenterStepOne?.let { attemptStateChange(it,2L) }
                        }
                        return@Runnable
                    }
                    else{
                        when (encoder.currentPosition) {
                            in 240..247 -> {
                                PWM.setPwmDutyCycle(PWM_MAX)
                                A2.gpio.value = true
                                B1.gpio.value = true
                                A1.gpio.value = false
                            }
                            in 253..260 -> {
                                PWM.setPwmDutyCycle(PWM_MAX)
                                A2.gpio.value = true
                                B1.gpio.value = false
                                A1.gpio.value = false
                            }
                            else -> {
//                                logger.log(TAG, "ELSE encoder position not in 245..247 or 253..255")
                                //setGpioLow()
                                //setPWMToZero()
                            }
                        }
                    }

                    if(!LEAFLOCK.gpio.value && (encoder.currentPosition in 230..270/*245..255*/)){
//                        PWM.setPwmDutyCycle(PWM_HALF)
                        if (PWM_DUTY_CYCLE == 100.0) {
                            PWM_DUTY_CYCLE = 50.0
                        }
                        else if (PWM_DUTY_CYCLE == 50.0) {
                            PWM_DUTY_CYCLE = 40.0
                        }

                        else if (PWM_DUTY_CYCLE == 40.0) {
                            PWM_DUTY_CYCLE = 30.0
                        }
                        else if (PWM_DUTY_CYCLE == 30.0) {
                            PWM_DUTY_CYCLE = 25.0
                        }


                        else if (PWM_DUTY_CYCLE == 25.0) {
                            PWM_DUTY_CYCLE = 20.0
                        }
                        else if (PWM_DUTY_CYCLE == 20.0) {
                            PWM_DUTY_CYCLE = 15.0
                        }


                        PWM.setPwmDutyCycle(PWM_DUTY_CYCLE)
                        logger.log(TAG, "PWM Duty Cycle = " + PWM_DUTY_CYCLE.toString() + " at time " + System.currentTimeMillis().toString())
 //                       val timer = object: CountDownTimer(500, 250) {
 //                           override fun onTick(millisUntilFinished: Long) {
 //                               Log.e("StartActivity", "TICK")
 //                           }

 //                           override fun onFinish() {

                        if ((encoder.speed == 0) && (encoder.currentPosition in /*249..251*/248..252)/* && (encoder.acc in -1..1)*/) {
                            if (encoder.acc /*in -1..1*/ == 0) {
                                ignore_reward=true
//                                logger.log("LEAFLOCK","LEAFLOCK extending at encoder value: " + encoder.currentPosition.toString() + " and speed " + encoder.speed.toString() + " with acceleration = " + encoder.acc.toString())
                                logger.log("LEAFLOCK","th_d_fc = " + th_d_fc.toString() + ", th_dd_fc = " + th_dd_fc.toString() + ", th_max_fc = " + th_max_fc.toString())
                                logger.log("LEAFLOCK","th_d_pc = " + th_d_pc.toString() + ", th_dd_pc = " + th_dd_pc.toString() + ", th_max_pc = " + th_max_pc.toString())
                                extendLeafLock()
                                removeNormalizeRunnables()
                                closeBarrierRunnable?.let {workerHandler.removeCallbacks(it)}
                                PWM.setPwmDutyCycle(0.0)
                            }
                            else {
                                logger.log("LEAFLOCK","Reducing pressure with acceleration = " + encoder.acc.toString())
                                PWM.setPwmDutyCycle(40.0)
                            }
                        }
                        else {
//                            logger.log(TAG, "ELSE condition: " + encoder.currentPosition.toString() + " and speed " + encoder.speed.toString() + " with acceleration = " + encoder.acc.toString())
                        }

 //                           }
 //                       }
 //                       timer.start()
                    }

                    closeBarrierRunnable?.let { attemptStateChange(it,200L) }
                } else {
                    checkForceBarrierTransitionComplete()
                    removeNormalizeRunnables()

                    if(DBLOCK.gpio.value && !encoder.isEncoderCenter()){
                        lockEngagedCount++
                        if(lockEngagedCount >= MAX_COUNT){
                            lockEngagedCount = 0
                            DBLOCK.gpio.value = false
                            logger.log(TAG,"barrier stuck and lock engaged; unlocking")
                        }
                    }
                    else{
                        lockEngagedCount = 0
                    }

                    if (encoder.isEncoderOpenTowardsFa()) {
                        //var file = File("/sdcard/Download/Reward.txt")
                        //file.createNewFile()
                        //file.appendText()
                        logger.log(TAG, "encoder.currentPosition = " + encoder.currentPosition)
                        closeDir = CloseDir.FA_TO_CENTER
                        val currentEncoderValue = (encoder.currentPosition).toDouble()
                        val endPoint = 250.0
                        val newDutyCycle = calculateDuty(currentEncoderValue,currentEncoderValue,endPoint,velocityFactorOpenFaToCenter,FA2CenterVelocityLevel)

                        if ((encoder.currentPosition > FA2CenterInitializedBrakingEncoderVal) && (barrierInitialized) && (encoder.speed > 0) && (!ignoreBraking)) {
                            A1.gpio.value = true
                            logger.log(TAG, "A1.gpio.value = true 7")
                            B1.gpio.value = true
                            A2.gpio.value = true
                            var limited_newDutyCycle = newDutyCycle * 5//20
                            if (limited_newDutyCycle > 100)
                                limited_newDutyCycle = 100.0
                            PWM.setPwmDutyCycle(limited_newDutyCycle)
                            //logger.log(TAG, "limited_newDutyCycle set to " + limited_newDutyCycle.toString())
                        }
                        else if ((encoder.currentPosition > FA2CenterUninitializedBrakingEncoderVal) && (!barrierInitialized) && (encoder.speed > 0)) {
                            A1.gpio.value = true
                            logger.log(TAG, "A1.gpio.value = true 8")
                            B1.gpio.value = true
                            A2.gpio.value = true
                            var limited_newDutyCycle = newDutyCycle * 5//20
                            if (limited_newDutyCycle > 100)
                                limited_newDutyCycle = 100.0
                            PWM.setPwmDutyCycle(limited_newDutyCycle)
                            //logger.log(TAG, "limited_newDutyCycle set to " + limited_newDutyCycle.toString())
                        }
                        else {
                            A1.gpio.value = false
                            B1.gpio.value = true
                            A2.gpio.value = true
                            PWM.setPwmDutyCycle(newDutyCycle)
                        }
                    }
                    if (encoder.isEncoderOpenTowardsPa()) {
                        closeDir = CloseDir.PA_TO_CENTER
                        val currentEncoderValue = (encoder.currentPosition).toDouble()
                        val endPoint = 250.0
                        val newDutyCycle = calculateDuty(currentEncoderValue,currentEncoderValue,endPoint,velocityFactorOpenPaToCenter,PA2CenterVelocityLevel)

                        if ((encoder.currentPosition < PA2CenterInitializedBrakingEncoderVal) && (barrierInitialized) && (encoder.speed < 0) && (!ignoreBraking)) {
                            A1.gpio.value = false
                            B1.gpio.value = true
                            A2.gpio.value = true
                            var limited_newDutyCycle = newDutyCycle * 5//20
                            if (limited_newDutyCycle > 100)
                                limited_newDutyCycle = 100.0
                            PWM.setPwmDutyCycle(limited_newDutyCycle)
                            //logger.log(TAG, "limited_newDutyCycle set to " + limited_newDutyCycle.toString())
                        }
                        else if ((encoder.currentPosition < PA2CenterUninitializedBrakingEncoderVal) && (!barrierInitialized) && (encoder.speed < 0)) {
                            A1.gpio.value = false
                            B1.gpio.value = true
                            A2.gpio.value = true
                            var limited_newDutyCycle = newDutyCycle * 5//20
                            if (limited_newDutyCycle > 100)
                                limited_newDutyCycle = 100.0
                            PWM.setPwmDutyCycle(limited_newDutyCycle)
                            //logger.log(TAG, "limited_newDutyCycle set to " + limited_newDutyCycle.toString())
                        }
                        else {
                            A1.gpio.value = false
                            B1.gpio.value = false
                            A2.gpio.value = true
                            PWM.setPwmDutyCycle(newDutyCycle)
                        }
                    }

                    setState(encoder.getEncoderBarrierState())
                    closeBarrierRunnable?.let { attemptStateChange(it,barrierStatePollTime) }
                }
            } else {
                logger.log(TAG, "center barrier called and encoder is NOT configured, using timers")

                when (currentState) {
                    BarrierState.OPEN_FA, BarrierState.BETWEEN_FA_CENTER -> run {
                        B1.gpio.value = true
                        A2.gpio.value = true
                        setState(BarrierState.BETWEEN_FA_CENTER)
                        PWM.setPwmDutyCycle(100.0)
                        sleepTime(5_000L)
                        for( i in 1..16){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "100% PWM BETWEEN_FA_CENTER i = " + i.toString())
                        }
                        PWM.setPwmDutyCycle(50.0)
                        for( i in 1..16){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "50% PWM BETWEEN_FA_CENTER i = " + i.toString())
                        }
                        PWM.setPwmDutyCycle(25.0)
                        for( i in 1..20){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "25% PWM BETWEEN_FA_CENTER i = " + i.toString())
                        }
                        PWM.setPwmDutyCycle(20.0)
                        for( i in 1..20){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "20% PWM BETWEEN_FA_CENTER i = " + i.toString())
                        }
                        PWM.setPwmDutyCycle(15.0)
                        for( i in 1..20){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "15% PWM BETWEEN_FA_CENTER i = " + i.toString())
                        }
                        if(!DBLOCK.gpio.value){
                            extendDbLock()
                        }
                        B1.gpio.value = false
                        setState(BarrierState.CENTER)
                        closeCalled = false
                    }
                    BarrierState.OPEN_PA, BarrierState.BETWEEN_PA_CENTER -> run {
                        A2.gpio.value = true
                        setState(BarrierState.BETWEEN_PA_CENTER)
                        PWM.setPwmDutyCycle(100.0)
                        sleepTime(5_000L)
                        for( i in 1..16){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "100% PWM BETWEEN_PA_CENTER i = " + i.toString())
                        }
                        PWM.setPwmDutyCycle(50.0)
                        for( i in 1..16){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "50% PWM BETWEEN_PA_CENTER i = " + i.toString())
                        }
                        PWM.setPwmDutyCycle(25.0)
                        for( i in 1..20){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "25% PWM BETWEEN_FA_CENTER i = " + i.toString())
                        }
                        PWM.setPwmDutyCycle(20.0)
                        for( i in 1..20){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "20% PWM BETWEEN_PA_CENTER i = " + i.toString())
                        }
                        PWM.setPwmDutyCycle(15.0)
                        for( i in 1..20){
                            B1.gpio.value = !B1.gpio.value
                            sleepTime(500L)
                            logger.log(TAG, "15% PWM BETWEEN_FA_CENTER i = " + i.toString())
                        }
                        if(!DBLOCK.gpio.value){
                            extendDbLock()
                        }
                        B1.gpio.value = false
                        setState(BarrierState.CENTER)
                        closeCalled = false
                    }
                    else -> {
                        logger.log(TAG, "Could not determine direction to center without encoder")
                    }
                }
            }
        }

    }

   var dutyMultiplier: Double = 1.0
   private fun calculateDuty(currentEncoderValue: Double, startPoint:Double, endPoint: Double,velocityFactorQueue: VelocityFactorQueue,velocityLevel:Int):Double {
       currentPosition = currentEncoderValue
       var multiplier = dutyMultiplier
       var velocityFactor: Double = if(velocityFactorQueue.minCapacityReached()) velocityFactorQueue.getAverageTime() / velocityLevel else 1.0

       if (barrierStartPosition == 0.0) {
           barrierStartPosition = startPoint
           previousPosition = currentPosition
           lastPositionChange = System.currentTimeMillis()
       }

       if ((abs(currentPosition - previousPosition) < MIN_CHANGE_VALUE) &&
               ((System.currentTimeMillis() - lastPositionChange) >= MIN_CHANGE_TIME)) {
           barrierStartPosition = currentPosition
           dutyMultiplier *= 1.4
       }
       else{
           dutyMultiplier = 1.0
       }

       if ((abs(currentPosition - previousPosition) >= MIN_CHANGE_VALUE)) {
           previousPosition = currentPosition
           lastPositionChange = System.currentTimeMillis()
       }

       val slope = calculateSlope(currentPosition,barrierStartPosition,endPoint)
       val change = (baseDutyCycle  * multiplier  * (velocityFactor * velocityFactor) * slope)

       return if (change > 100.0) 100.0 else change
   }

  private fun calculateSlope(currentPoint:Double, startPoint:Double, endPoint:Double): Double{
       if( endPoint > startPoint){
          var slope = (endPoint  - currentPoint) / ( endPoint - startPoint)
           if (slope < 0) {
               slope = 0.0
           } else if (slope > 1) {
               slope = 1.0
           }
           return slope.pow(5.0)  // * slope * slope
       }
       else{
           var slope =  (currentPoint - endPoint) / ( startPoint - endPoint)
           if(slope < 0){
               slope = 0.0
           }
           else if(slope > 1){
               slope = 1.0
           }
           return slope.pow(5.0)
       }
   }

   override fun setGpioLow() {
       A1.gpio.value = false
       A2.gpio.value = false
       B1.gpio.value = false
   }

   override fun enablePWM() {
       PWM.setEnabled(true)
   }

   override fun disablePWM() {
       PWM.setEnabled(false)
   }

   override fun setPWMToZero(){
       PWM.setPwmDutyCycle(PWM_MIN)
   }

   private fun removeNormalizeRunnables(){
       normalizeCenterStepOne?.let {  workerHandler.removeCallbacks(it) }
       normalizeCenterStepTwo?.let {  workerHandler.removeCallbacks(it) }
       normalizeCenterStep3?.let {  workerHandler.removeCallbacks(it) }
       normalizeCenterStep4?.let {  workerHandler.removeCallbacks(it) }
   }

   override fun closeBarrier() {
       if(!closeCalled) {
           PWM_DUTY_CYCLE = 100.0
           barrierStartPosition = 0.0
           closeCalled = true
           openCalled = false
           ignoreBraking = false
           retractionStarted = false
           setGpioLow()
           closeDir = when {
               encoder.isEncoderOpenTowardsPa() -> {
                   CloseDir.PA_TO_CENTER
               }
               encoder.isEncoderOpenTowardsFa() -> {
                   CloseDir.FA_TO_CENTER
               }
               encoder.isEncoderCenter() -> {
                   CloseDir.CENTERED
               }
               else -> {
                   CloseDir.UNKNOWN
               }
           }
           closeBarrierRunnable?.let { outer ->
               removeNormalizeRunnables()
               ensureBarrierCloses?.let { workerHandler.removeCallbacks(it) }
               openBarrierFaRunnable?.let { workerHandler.removeCallbacks(it) }
               openBarrierPaRunnable?.let { workerHandler.removeCallbacks(it) }
               attemptStateChange(outer,5L)
           }
       }
   }

   override fun openBarrierPa() {
       if(!openCalled) {
           barrierStartPosition = 0.0
           openCalled = true
           closeCalled = false
           ignoreBraking = false
           setGpioLow()
           closeDir =  CloseDir.PA_TO_CENTER
           //logger.log(TAG, "openBarrierPa()")

           openBarrierPaRunnable?.let { outer ->
               removeNormalizeRunnables()
               ensureBarrierCloses?.let { workerHandler.removeCallbacks(it) }
               closeBarrierRunnable?.let { workerHandler.removeCallbacks(it) }
               openBarrierFaRunnable?.let { workerHandler.removeCallbacks(it) }
               attemptStateChange(outer,5L)
           }
       }
   }

   override fun openBarrierFa() {
       if(!openCalled) {
           barrierStartPosition = 0.0
           openCalled = true
           closeCalled = false
           ignoreBraking = false
           setGpioLow()
           closeDir =  CloseDir.FA_TO_CENTER

           openBarrierFaRunnable?.let { outer ->
               removeNormalizeRunnables()
               ensureBarrierCloses?.let { workerHandler.removeCallbacks(it) }
               closeBarrierRunnable?.let { workerHandler.removeCallbacks(it) }
               openBarrierPaRunnable?.let { workerHandler.removeCallbacks(it) }
               attemptStateChange(outer,5L)
           }
       }
   }

   override fun extendDbLock() {
       DBLOCK.gpio.value = true
       retractionStarted = false
   }

   override fun retractDbLock() {
       DBLOCK.gpio.value = false
       setGpioLow()
   }

   override fun extendLeafLock() {
       Log.i(TAG, "extendLeafLock called")
       LEAFLOCK.gpio.value = true
       ignoreBraking = false
 //      closeBarrierRunnable?.let {workerHandler.removeCallbacks(it)}
       readBrakingValues()
   }

   override fun retractLeafLock() {
       Log.i(TAG, "retractLeafLock called")
       LEAFLOCK.gpio.value = false
   }

   override fun isBarrierClosedAndLocked(): Boolean {
       return currentState == BarrierState.CENTER && DBLOCK.gpio.value
   }

   override fun isBarrierFullyOpened(): Boolean {
       return currentState == BarrierState.OPEN_FA || currentState == BarrierState.OPEN_PA
   }

   override fun getEncoder(): Encoder {
       return encoder
   }

   override fun isInitialized(): Boolean {
       return encoder.isEncoderConfigurationComplete()
   }

   override fun isCenterFound(): Boolean {
       return encoder.centerValueFound
   }

   override fun isOpenPaFound(): Boolean {
       return encoder.openPaValueFound
   }

   override fun isOpenFaFound(): Boolean {
       return encoder.openFaValueFound
   }

   override fun getEncoderRanges(): String {
       val rangesMap = encoder.getEncoderRanges()
       var potentialError = false
       for ((_, ranges) in rangesMap) {
           for (range in ranges) {
               if (range.first >= range.last) {
                   potentialError = true
               }
           }
       }
       return if (potentialError) {
           "POTENTIAL ENCODER ERROR CHECK VALUES: ${rangesMap.pTS()}"
       } else {
           "${rangesMap.pTS()}"
       }
   }

   override fun onDestroy() {
       encoder.onDestroy()
       setGpioLow()
       workerThread.quitSafely()
   }
}
