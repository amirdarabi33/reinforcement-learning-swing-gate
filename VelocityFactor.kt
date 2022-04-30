package gov.bart.fce.bas.barrier

import gov.bart.fce.bas.BarrierAisleSystem
import gov.bart.fce.bas.BasLogger

data class VelocityAdded(val isError: Boolean, val message: String)

class VelocityFactorQueue(private val name:String,private val maxSize:Int,private val initCount:Int = 2, private val diff:Double = 50.0 ){
    private val queue = mutableListOf<Long>()

    private var started = false
    private var startTime = 0L
    private var stopTime = 0L

    fun start(){
        if(!started){
            started = true;
            startTime = System.currentTimeMillis()
        }
    }

    fun stop(){
        if(started){
            started = false
            stopTime = System.currentTimeMillis()
            val result =  add(stopTime - startTime)
            if(!result.isError){
                BasLogger.getInstance().log(name,result.toString())
            }
        }
    }

    private fun add(time:Long): VelocityAdded {
        val maxTime = (diff * getAverageTime())
        val minTime = diff * time

        return if(queue.size < initCount || (maxTime >= time && minTime  >= getAverageTime() ) ){
            if(queue.size < maxSize){
                queue.add(time)
            }
            else{
                queue.removeAt(0)
                queue.add(time)
            }
            VelocityAdded(false,"Good:$time within ${diff}X difference of Avg:${getAverageTime()}; Queue Size=${queue.size}")
        }
        else{
            return if(minTime  <= getAverageTime()){
                VelocityAdded(true, "(Rejected:[$time] X $diff = ($minTime < Avg=${getAverageTime()}); Queue Size=${queue.size}")
            } else {
                VelocityAdded(true, "(Rejected:[$time] > Max:$maxTime; Avg=${getAverageTime()}; Queue Size=${queue.size}")
            }
        }
    }

    fun minCapacityReached():Boolean{
        return queue.size >= initCount;
    }

    fun getAverageTime():Double{
        return queue.average()
    }

    override fun toString(): String {
        return "VelocityFactorQueue: Avg: ${getAverageTime()} Size: ${queue.size}"
    }

    fun toStringAll(): String {
        return "VelocityFactorQueue: Avg: ${getAverageTime()} Size: ${queue.size} $queue"
    }
}
