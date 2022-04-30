package gov.bart.fce.bas.encoder

import gov.bart.fce.bas.barrier.BarrierState
import kotlin.math.abs

data class EncoderRangeValues(val encoderValue: Int, val rangeStart: Int, val rangeEnd: Int, val rangeSize: Int, val rollOver: Boolean, val ranges: List<IntProgression>)

data class EncoderValuesAndRangesHolder(val nonAdjusted: EncoderValuesAndRanges, val adjusted: EncoderValuesAndRanges){
    private val rangesNonAdjusted = linkedSetOf<Int>()
    private val rangesAdjusted = linkedSetOf<Int>()
    init{
        nonAdjusted.openFa.ranges.forEach { range ->
            range.forEach { rangesNonAdjusted.add(it) }
        }
        nonAdjusted.betweenFaCenter.ranges.forEach {range ->
            range.forEach { rangesNonAdjusted.add(it) } }
        nonAdjusted.center.ranges.forEach { range ->
            range.forEach { rangesNonAdjusted.add(it) } }
        nonAdjusted.betweenPaCenter.ranges.forEach { range ->
            range.forEach { rangesNonAdjusted.add(it) } }
        nonAdjusted.openPa.ranges.forEach { range ->
            range.forEach { rangesNonAdjusted.add(it) } }

        adjusted.openFa.ranges.forEach { range ->
            range.forEach { rangesAdjusted.add(it) } }
        adjusted.betweenFaCenter.ranges.forEach { range ->
            range.forEach { rangesAdjusted.add(it) } }
        adjusted.center.ranges.forEach { range ->
            range.forEach { rangesAdjusted.add(it) } }
        adjusted.betweenPaCenter.ranges.forEach { range ->
            range.forEach { rangesAdjusted.add(it) } }
        adjusted.openPa.ranges.forEach { range ->
            range.forEach { rangesAdjusted.add(it) } }
    }
    fun translateNonAdjustedToAdjusted(unAdjustedValue:Int):Int{
        rangesNonAdjusted.forEachIndexed { rangeIndexNon, nonAdjValue ->
            if(nonAdjValue == unAdjustedValue){
                return rangesAdjusted.elementAt(rangeIndexNon)
            }
        }
        return 1024
    }
}

data class EncoderValuesAndRanges(val values:EncoderValues, val openFa: EncoderRangeValues, val betweenFaCenter: EncoderRangeValues,val center: EncoderRangeValues,val betweenPaCenter: EncoderRangeValues,val openPa: EncoderRangeValues, val totalRangeSize:Int){
    override fun toString():String{
        return "openFa=${openFa.ranges.pTS()} betweenFaCenter=${betweenFaCenter.ranges.pTS()} center=${center.ranges.pTS()} betweenPaCenter=${betweenPaCenter.ranges.pTS()} openPaRange=${openPa.ranges.pTS()} SIZE=$totalRangeSize"
    }
}


//// START of Extension functions to make printing ranges easier to read
fun IntProgression.prettyToString():String = "$first..$last"

fun List<IntProgression>.pTS(): String  = if(size == 1) "[${this[0].prettyToString()}]" else "[${this[0].prettyToString()},${this[1].prettyToString()} ]"

fun LinkedHashMap<BarrierState, List<IntProgression>>.pTS(): String {
    val i: Iterator<Map.Entry<BarrierState, List<IntProgression>>> = entries.iterator()
    if (!i.hasNext()) return "{}"
    val sb = StringBuilder()
    sb.append('{')
    while (true) {
        val e: Map.Entry<BarrierState, List<IntProgression>> = i.next()
        val key: BarrierState = e.key
        val value: List<IntProgression> = e.value
        if(key.toString().length < 8){
            sb.append("%-7s=%-16s".format(if (key === this) "(this Map)" else key,if (value === this) "(this Map)" else value.pTS()))
        }
        else{
            sb.append("%-15s=%-18s".format(if (key === this) "(this Map)" else key,if (value === this) "(this Map)" else value.pTS()))
        }
        sb.trimEnd()
        if (!i.hasNext()) return sb.append('}').toString()
        sb.append(' ')
    }
}
//// END of Extension functions to make printing ranges easier to read


data class EncoderValues private constructor(val openFa: Int, val center: Int, val openPa: Int, val state: EncoderPositionState, val ascending:Boolean){
    companion object {
        fun create(openFa: Int, center: Int, openPa: Int): EncoderValues {
            return if (openFa > center && openFa < openPa) {
                EncoderValues(openFa, center, openPa, EncoderPositionState.STATE1, false)
            } else if (openFa > openPa && center < openPa) {
                EncoderValues(openFa, center, openPa, EncoderPositionState.STATE2, true)
            } else if (openFa < center && center < openPa) {
                EncoderValues(openFa, center, openPa, EncoderPositionState.STATE3, true)
            } else if (openFa > center && center > openPa) {
                EncoderValues(openFa, center, openPa, EncoderPositionState.STATE4, false)
            } else if (openFa < openPa && center > openPa) {
                EncoderValues(openFa, center, openPa, EncoderPositionState.STATE5, false)
            } else {
                EncoderValues(openFa, center, openPa, EncoderPositionState.STATE6, true)
            }
        }
    }
}

/**
 * STATES | F | C | P | The combination of all 3 possible values (in relation to order by size) for encoder positions; left to right.
 *
 * STATE1 | 1 | 0 | 2 | descending; ex: 128 -> 0   -> 383
 *
 * STATE2 | 2 | 0 | 1 | ascending;  ex: 383 -> 0   -> 128
 *
 * STATE3 | 0 | 1 | 2 | ascending;  ex: 0   -> 128 -> 256
 *
 * STATE4 | 2 | 1 | 0 | descending; ex: 256 -> 128 -> 0
 *
 * STATE5 | 0 | 2 | 1 | descending;  ex: 130 -> 505 -> 365
 *
 * STATE6 | 1 | 2 | 0 | ascending;  ex: 365 -> 505 -> 130
 */
enum class EncoderPositionState {
    STATE1,
    STATE2,
    STATE3,
    STATE4,
    STATE5,
    STATE6
}

fun calculateEncoderValuesAndRanges(encoderValues: EncoderValues):EncoderValuesAndRanges{
    val openFaRanges = calculateEncoderPositionValues(encoderValues.openFa,encoderValues.ascending,false)
    val centerRanges = calculateEncoderPositionValues(encoderValues.center,encoderValues.ascending,true)
    val openPaRanges = calculateEncoderPositionValues(encoderValues.openPa,encoderValues.ascending,false)

    val alreadyRollover = (openFaRanges.rollOver || centerRanges.rollOver || openPaRanges.rollOver )
    val betweenFaCenter = calculateBetweenRange(openFaRanges.rangeEnd,centerRanges.rangeStart,encoderValues.ascending, alreadyRollover)
    val betweenPaCenter = calculateBetweenRange(centerRanges.rangeEnd,openPaRanges.rangeStart,encoderValues.ascending, alreadyRollover)

    val rangeSize = openFaRanges.rangeSize + betweenFaCenter.rangeSize + centerRanges.rangeSize + betweenPaCenter.rangeSize + openPaRanges.rangeSize;

    return EncoderValuesAndRanges(encoderValues,openFaRanges,betweenFaCenter,centerRanges,betweenPaCenter,openPaRanges,rangeSize)
}

fun calculateBetweenRange(start:Int, end:Int, ascending: Boolean,alreadyRollover:Boolean):EncoderRangeValues{
    val betweenRange = if(alreadyRollover){
        // roll over already happened, no need to for extra checks here
        if(ascending){
            listOf(start+1 until end)
        }
        else{
            listOf(start-1 downTo end+1)
        }
    }
    else{
        // roll over has not happened, need to check and properly calc
        if(ascending){
            if(start > end){
                // means roll over 500+  > 0+ ish
                listOf(start+1..Encoder.maxRes, Encoder.minRes until end)
            }
            else{
                // no roll over
                listOf(start+1 until end)
            }
        }
        else{
            // descending now
            if(end > start){
                // means roll over 500ish > 0ish
                listOf(start-1 downTo Encoder.minRes, Encoder.maxRes downTo end+1)
            }
            else{
                listOf(start-1 downTo end+1)
            }
        }
    }

    val bStart =  betweenRange[0].first
    val bEnd = if(betweenRange.size == 1) betweenRange[0].last else betweenRange[1].last
    var rangeSize = if (betweenRange.size == 1) betweenRange[0].count() else betweenRange[0].count() + betweenRange[1].count()

    return EncoderRangeValues(-1,bStart,bEnd,rangeSize,betweenRange.size == 2,betweenRange)
}

/**
 *  Takes an encoder value in the range of 0 to 511 and generates the EncoderPositionValues
 *
 *  By default, forCenter is false, if true will use the [Encoder.centerRange] rather than the [Encoder.openRange]
 */
fun calculateEncoderPositionValues(encoderValue: Int,ascending: Boolean, forCenter: Boolean = false): EncoderRangeValues {
    val minLower = if (forCenter) (encoderValue - Encoder.centerRange) else (encoderValue - Encoder.openRange)
    val maxUpper = if (forCenter) (encoderValue + Encoder.centerRange) else (encoderValue + Encoder.openRange)
    val totalRange = if (forCenter) (2 * Encoder.centerRange) else (2 * Encoder.openRange)

    return when {
        minLower !in Encoder.resolution -> {
            val taken = abs(Encoder.minRes - minLower)
            var remaining = totalRange - taken

            val leftProgression = if(ascending){
                (((Encoder.maxRes - taken) + 1)..Encoder.maxRes)
            } else{
                (Encoder.maxRes downTo ((Encoder.maxRes - taken) + 1))
            }

            val rightProgression = if(ascending) {
                Encoder.minRes..remaining
            } else{
                remaining downTo Encoder.minRes
            }

            val rangeSize = if(ascending){
                leftProgression.count() + rightProgression.count()
            }
            else{
                leftProgression.reversed().count() + rightProgression.reversed().count()
            }
            if (leftProgression.last < rightProgression.first || !ascending) {
                EncoderRangeValues(encoderValue, rightProgression.first, leftProgression.last, rangeSize, true, listOf(rightProgression, leftProgression))
            } else {
                EncoderRangeValues(encoderValue, leftProgression.first, rightProgression.last, rangeSize, true, listOf(leftProgression, rightProgression))
            }
        }
        maxUpper !in Encoder.resolution -> {
            var taken = abs((Encoder.maxRes) - maxUpper)
            val remaining = totalRange - taken

            val leftProgression = if(ascending) {
                (Encoder.minRes until (Encoder.minRes + taken))
            }
            else{
                ((Encoder.minRes + taken -1) downTo  Encoder.minRes)
            }
            val rightProgression = if(ascending) {
                ((Encoder.maxRes - remaining))..Encoder.maxRes
            }
            else{
                Encoder.maxRes downTo ((Encoder.maxRes - remaining))
            }


            val rangeSize = leftProgression.count() + rightProgression.count()
            if(ascending){
                if (leftProgression.last < rightProgression.first ) {
                    EncoderRangeValues(encoderValue, rightProgression.first, leftProgression.last, rangeSize, true, listOf(rightProgression, leftProgression))

                } else {
                    EncoderRangeValues(encoderValue, leftProgression.first, rightProgression.last, rangeSize, true, listOf(leftProgression, rightProgression))
                }
            }
            else{
                if (leftProgression.last < rightProgression.first ) {
                    EncoderRangeValues(encoderValue, leftProgression.first, rightProgression.last, rangeSize, true, listOf(leftProgression, rightProgression))
                } else {
                    EncoderRangeValues(encoderValue, rightProgression.first, leftProgression.last, rangeSize, true, listOf(rightProgression, leftProgression))
                }
            }
        }
        else -> {
            val rangeSize = (minLower..maxUpper).count()
            if(ascending){
                EncoderRangeValues(encoderValue, minLower, maxUpper, rangeSize, false, listOf(minLower..maxUpper))
            }
            else{
                EncoderRangeValues(encoderValue, maxUpper,minLower , rangeSize, false, listOf(maxUpper downTo minLower))
            }
        }
    }
}

fun adjustEncoderValues(encoderValuesAndRange:EncoderValuesAndRanges):EncoderValuesAndRanges{
    val centerPoint = 250
    val openFa = encoderValuesAndRange.values.openFa
    val center = encoderValuesAndRange.values.center
    val openPa = encoderValuesAndRange.values.openPa

    return when (encoderValuesAndRange.values.state) {
        EncoderPositionState.STATE1 -> {
            // descending
            var diffOpenFa = (openFa - center) + centerPoint
            var diffOpenPa = centerPoint - (center + (Encoder.maxRes - openPa) + 1)
            val t = EncoderValues.create(diffOpenFa, centerPoint, diffOpenPa)
            return calculateEncoderValuesAndRanges(t)
        }
        EncoderPositionState.STATE2 -> {
            // ascending
            var diffOpenFa = centerPoint - ((Encoder.maxRes - openFa) + (center) + 1)
            var diffOpenPa = centerPoint + (openPa - center)
            val t = EncoderValues.create(diffOpenFa, centerPoint, diffOpenPa)
            return calculateEncoderValuesAndRanges(t)
        }
        EncoderPositionState.STATE3 -> {
            // ascending
            var diffOpenFa = centerPoint - (center - openFa)
            var diffOpenPa = centerPoint + (openPa - center )
            val t = EncoderValues.create(diffOpenFa, centerPoint, diffOpenPa)
            return calculateEncoderValuesAndRanges(t)
        }
        EncoderPositionState.STATE4 -> {
            // descending
            var diffOpenFa = centerPoint + (openFa - center)
            var diffOpenPa = centerPoint + (openPa - center)
            val t = EncoderValues.create(diffOpenFa, centerPoint, diffOpenPa)
            return calculateEncoderValuesAndRanges(t)
        }
        EncoderPositionState.STATE5 -> {
            // descending
            var diffOpenFa = centerPoint - ((Encoder.maxRes - center) + (openFa) + 1)
            var diffOpenPa = centerPoint + (center - openPa)
            val t = EncoderValues.create(diffOpenFa, centerPoint, diffOpenPa)
            return  calculateEncoderValuesAndRanges(t)
        }
        EncoderPositionState.STATE6 -> {
            // ascending
            var diffOpenFa = centerPoint - (center - openFa)
            var diffOpenPa = centerPoint + ((Encoder.maxRes - center) + (openPa) + 1)
            val t = EncoderValues.create(diffOpenFa, centerPoint, diffOpenPa)
            return  calculateEncoderValuesAndRanges(t)
        }
    }
}

fun checkForError(encoderValuesAndRanges: EncoderValuesAndRangesHolder):Pair<Boolean,String>{

    val nonAdjusted = encoderValuesAndRanges.nonAdjusted
    val adjusted = encoderValuesAndRanges.adjusted

    return when {
        nonAdjusted.totalRangeSize != adjusted.totalRangeSize -> {
            Pair(true,"Range Sizes do not match: NonAdjusted: ${nonAdjusted.totalRangeSize} Adjusted: ${adjusted.totalRangeSize}")
        }
        abs(nonAdjusted.openFa.rangeEnd - nonAdjusted.betweenFaCenter.rangeStart) > 1 -> {

            Pair(true,"Error in nonAdjusted range between openFa and betweenFaCenter : $nonAdjusted")
        }
        abs(nonAdjusted.betweenFaCenter.rangeEnd - nonAdjusted.center.rangeStart) > 1 -> {
            Pair(true,"Error in nonAdjusted range between betweenFaCenter and center : $nonAdjusted")
        }
        abs(nonAdjusted.center.rangeEnd - nonAdjusted.betweenPaCenter.rangeStart) > 1 -> {
            Pair(true,"Error in nonAdjusted range between center and betweenPaCenter : $nonAdjusted")
        }
        abs(nonAdjusted.betweenPaCenter.rangeEnd - nonAdjusted.openPa.rangeStart) > 1 -> {
            Pair(true,"Error in nonAdjusted range between betweenPaCenter and openPa : $nonAdjusted")
        }
////
        abs(adjusted.openFa.rangeEnd - adjusted.betweenFaCenter.rangeStart) > 1 -> {
            Pair(true,"Error in adjusted range between openFa and betweenFaCenter : $adjusted")
        }
        abs(adjusted.betweenFaCenter.rangeEnd - adjusted.center.rangeStart) > 1 -> {
            Pair(true,"Error in adjusted range between betweenFaCenter and center : $adjusted")
        }
        abs(adjusted.center.rangeEnd - adjusted.betweenPaCenter.rangeStart) > 1 -> {
            Pair(true,"Error in adjusted range between center and betweenPaCenter : $adjusted")
        }
        abs(adjusted.betweenPaCenter.rangeEnd - adjusted.openPa.rangeStart) > 1 -> {
            Pair(true,"Error in adjusted range between betweenPaCenter and openPa : $adjusted")
        }
        else -> {
            Pair(false,"No Error")
        }
    }
}

fun grayCodeToBin(g: Int): Int {
    var bin = 0
    var gray = g

    while (gray != 0) {
        val dec = gray - 1
        bin = bin xor gray
        bin = bin xor dec
        gray = gray and dec
    }

    return bin
}

// from Tri-Tronic Co Inc SR12 Absolute EncoderSerial Peripheral Interface pdf
val  encoderBit0Clear = 0b00_111111
val  encoderBit1Clear = 0b00_111000
// msb has 11 as bit pos 7 and 6
// other has 10 at bit pos 7 6
val  msbId= 0b11_000000

// this can be used if I figure out how to do roll over correctly
fun findGrayCode(encoderValues: ByteArray):Int{
    var b0 = encoderValues[0].toInt()
    var b1 = encoderValues[1].toInt()

    b0 = b0 and encoderBit0Clear
    b0 = b0 shl 3
    b1 = b1 and encoderBit1Clear
    b1 = b1 shr 3

    val combined =  b0 or b1
    return combined
}
