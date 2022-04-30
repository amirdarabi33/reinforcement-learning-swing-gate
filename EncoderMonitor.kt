package gov.bart.fce.bas.encoder

import gov.bart.fce.bas.BasLogger
import gov.bart.fce.bas.barrier.BarrierState

class EncoderMonitor(private val encoder: Encoder) {

    private val logger = BasLogger.getInstance()
    val TAG = encoder.TAG

    var barrierState = BarrierState.UNKNOWN

    private val pollTime = 2L
    private val checkConfiguredTime = 500L
    private val workerHandler = encoder.workerHandler
    private var lastlogged = 0L
    private var printStateCount = 3_000 //  (0..printStateCount = (printStateCount +1)) * pollTime ms = Prints state every 30 seconds
    private var minCountForStateChange = 2 //  (0..minCountForStateChange = (minCountForStateChange + 1)) * pollTime ms = Updates state if same state for 2 ms

    private lateinit var encoderValueLooper: Runnable
    private lateinit var waitForEncoderInitialization: Runnable
    private var waitForEncoderInitializationCount = 1
    private val stateCountMap = mutableMapOf(
        BarrierState.OPEN_FA to 0,
        BarrierState.BETWEEN_FA_CENTER to 0,
        BarrierState.CENTER to 0,
        BarrierState.BETWEEN_PA_CENTER to 0,
        BarrierState.OPEN_PA to 0
    )

    init {

        // runnable that loops until configured and then start encoder value polling
        waitForEncoderInitialization = Runnable {
            if (encoder.isEncoderConfigurationComplete()) {
                logger.log(TAG, "Encoder configured, starting encoderValuePoller")
                workerHandler.postDelayed(
                    encoderValueLooper,
                    pollTime
                )
            } else {
                waitForEncoderInitializationCount += 1
                if (waitForEncoderInitializationCount % 21 == 0) {
                    // print that still waiting for encoder to be configured every 5 seconds
                    logger.log(TAG, "waiting for encoder to be configured.; current values ${encoder.getEncoderRanges().pTS()}}")
                    waitForEncoderInitializationCount = 0
                }
                workerHandler.postDelayed(
                    waitForEncoderInitialization,
                    checkConfiguredTime
                )
            }
        }

        workerHandler.postDelayed(
            waitForEncoderInitialization,
            checkConfiguredTime
        )

        encoderValueLooper = Runnable {
            val now = System.currentTimeMillis()
            val currentEncoderValue: Int = encoder.currentPosition
            val unadjusted : Int = encoder.unadjusted
            val rangeMap = encoder.getEncoderRanges()
            when (determinePosition(currentEncoderValue,rangeMap)) {
                BarrierState.CENTER -> {
                    addCenter()
                }
                BarrierState.OPEN_FA -> {
                    addOpenFa()
                }
                BarrierState.OPEN_PA -> {
                    addOpenPa()
                }
                BarrierState.BETWEEN_FA_CENTER -> {
                    addBetweenCenterAndFa()
                }
                BarrierState.BETWEEN_PA_CENTER -> {
                    addBetweenCenterAndPa()
                }
                else -> {
                    if (now - lastlogged >= 5000) {
                        logger.log(TAG, "Encoder ranges are: ${rangeMap.pTS()}")
                        logger.log(
                            TAG,
                            "Could not determine position from unadjusted: $unadjusted to adjusted encoder value: $currentEncoderValue"
                        )
                        lastlogged = now
                    }
                }
            }

            workerHandler.postDelayed(
                encoderValueLooper,
                pollTime
            )
        }

    }

    private fun updateCountMap(updateToState: BarrierState) {
        for ((state, count) in stateCountMap) {
            if (state == updateToState) {
                stateCountMap[state] = count + 1
                if (count >= minCountForStateChange && barrierState != updateToState) {
                    barrierState = updateToState
                  //  logger.log(TAG, "State is now $barrierState with encoder value of ${encoder.currentPosition} with velocity factor of ${encoder.barrier.velocityFactor}")
                }
                if (count >= printStateCount) {
                    stateCountMap[state] = 0
                  //  logger.log(TAG, "State is $barrierState with encoder value of ${encoder.currentPosition} with velocity factor of ${encoder.barrier.velocityFactor} ")
                }
            } else {
                stateCountMap[state] = 0
            }
        }
    }

    private fun addCenter() {
        updateCountMap(BarrierState.CENTER)
    }

    private fun addOpenPa() {
        updateCountMap(BarrierState.OPEN_PA)
    }

    private fun addOpenFa() {
        updateCountMap(BarrierState.OPEN_FA)
    }

    private fun addBetweenCenterAndPa() {
        updateCountMap(BarrierState.BETWEEN_PA_CENTER)
    }

    private fun addBetweenCenterAndFa() {
        updateCountMap(BarrierState.BETWEEN_FA_CENTER)
    }

    private fun determinePosition(
        encoderPosition: Int,
        linkedRangeMap: LinkedHashMap<BarrierState, List<IntProgression>>
    ): BarrierState {

        for ((state, ranges) in linkedRangeMap) {
            for (range in ranges) {
                if (encoderPosition in range) {
                    return state
                }
            }
        }

        return BarrierState.UNKNOWN
    }

}
