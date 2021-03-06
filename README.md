# reinforcement-learning-swing-gate

In this project, a controller is being designed and implemented for a barrier in the faregate. One optimal controller would open the gate in 2.5 seconds after a patron tags his clipper card without slamming the console at the end of the cycle. Then, the gate will be closing towards the center in 2.5 seconds with minimal overshoot. Finally, the leaf lock at the edge of the barrier, will lock it to the end console. 

Since we don’t have any knowledge on the dynamics of the system (a purely pneumatic system composed of an air compressor and an actuator which supports the barrier rotation as a function of pressure, set by pressure regulator), a reinforcement learning method could be the best solution to design this controller.

Same as self-driving cars, the speed of this barrier are a function of the input pressure (same as pushing the gas pedal in the car), and in order to stop the barrier at the location of the leaf lock, the braking should be applied at the right time to avoid any overshoot. In order to stop the barrier, the second piston of the actuator is sent to the opposite direction, and the air compressed between the cylinders will act as a brake.  

As such, first the designed gate was tested and run in the BART research lab for 5000 cycles to map the decreasing pressure as an input function to the corresponding time response. Later, the input and output were reversed and fed into a deep neural network. After training the network, the desired time profile was given to the network and the corresponding pressure function was obtained to be set as the initial guess for the controller.

Since these gates are installed at different stations with random environment conditions (e.g., wind direction and its speed), those values picked up by the neural network might not work at all those locations. To address this issue, a reinforcement learning controller based on cross-entropy method will adjust the pressure and the proper braking time based on a designed reward function to overcome these unwanted conditions. 

The reward function is composed of three different parts, each representing one desired condition. Since no overshoot is desired, the speed and the acceleration of the barriers are constantly checked by the encoder, and the controller will be penalized each time those values are not zero at the leaf lock dead stop location. In addition, the stopping point for the mechanism is checked and the controller will assign a negative reward function for each degree which is off from the center value. Last part of the reward function takes care of the different timing between the entire cycle (from one side to the center) and the 2.5 seconds which is the desired value.        

The attached picture shows a screen of the sofware, which is used to give information regarding the status of the controller to the technicians. 
