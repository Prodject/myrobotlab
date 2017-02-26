package org.myrobotlab.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.myrobotlab.framework.Service;
import org.myrobotlab.framework.ServiceType;
import org.myrobotlab.genetic.GeneticAlgorithm;
import org.myrobotlab.genetic.Chromosome;
import org.myrobotlab.genetic.Genetic;
import org.myrobotlab.kinematics.CollisionDectection;
import org.myrobotlab.kinematics.CollisionItem;
import org.myrobotlab.kinematics.DHLink;
import org.myrobotlab.kinematics.DHRobotArm;
import org.myrobotlab.kinematics.Map3D;
import org.myrobotlab.kinematics.Map3DPoint;
import org.myrobotlab.kinematics.Matrix;
import org.myrobotlab.kinematics.Point;
import org.myrobotlab.kinematics.TestJmeIntegratedMovement;
import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.math.Mapper;
import org.myrobotlab.math.MathUtils;
import org.myrobotlab.openni.OpenNiData;
import org.myrobotlab.service.Servo.IKData;
import org.myrobotlab.service.interfaces.IKJointAnglePublisher;
import org.myrobotlab.service.interfaces.PointsListener;
import org.slf4j.Logger;

/**
 * 
 * IntegratedMovement - This class provides a 3D based inverse kinematics
 * implementation that allows you to specify the robot arm geometry based on DH
 * Parameters. The work is based on InversedKinematics3D by kwatters with different computation and goal,
 * including collision detection and moveToObject
 * 
 * Rotation and Orientation information is not currently supported. (but should
 * be easy to add)
 *
 * @author Christian/Calamity
 * 
 */
public class IntegratedMovement extends Service implements IKJointAnglePublisher, PointsListener, Genetic {

  private static final long serialVersionUID = 1L;
  public final static Logger log = LoggerFactory.getLogger(InverseKinematics3D.class.getCanonicalName());

  private DHRobotArm currentArm = null;
  
  private HashMap<String, DHRobotArm> arms = new HashMap<String, DHRobotArm>();

  private Matrix inputMatrix = null;

  Point goTo;
  private CollisionDectection collisionItems = new CollisionDectection();
  
  public static final int IK_COMPUTE_METHOD_PI_JACOBIAN = 1;
  public static final int IK_COMPUTE_METHOD_GENETIC_ALGORYTHM = 2;
  
  private int computeMethod = IK_COMPUTE_METHOD_PI_JACOBIAN;
  private int geneticPoolSize = 200;
  private double geneticMutationRate = 0.01;
  private double geneticRecombinationRate = 0.7;
  private int geneticGeneration = 300;
  private boolean geneticComputeSimulation = false;

  private HashMap<String, Servo> currentServos = new HashMap<String, Servo>();
  //private HashMap<String, HashMap<String, Servo>> servos = new HashMap<String, HashMap<String, Servo>>();
  private double time;
  
  private boolean stopMoving = false;		
	
  public enum ObjectPointLocation {		
  	ORIGIN_CENTER (0x01,"Center Origin"),		
  	ORIGIN_SIDE (0x02, "Side Origin"),		
  	END_SIDE(0x04, "Side End"),		
  	END_CENTER(0x05, "Center End"),		
  	CLOSEST_POINT(0x06, "Closest Point"),		
  	CENTER(0x07, "Center"),		
  	CENTER_SIDE(0x08, "Side Center");		
  	public int value;		
  	public String location;
  	private ObjectPointLocation(int value, String location) {		
  		this.value = value;
  		this.location = location;
  	}		
  }		
  		
  private class MoveInfo {		
  	Point offset = null;		
  	CollisionItem targetItem = null;		
  	ObjectPointLocation objectLocation = null;		
  	DHLink lastLink = null;		
  }		
  		
  private MoveInfo moveInfo = null;
	private OpenNi openni = null;
	
	public Map3D map3d = new Map3D();
	private String kinectName = "kinect";
	private boolean ProcessKinectData = false;
	
	TestJmeIntegratedMovement jmeApp = null;
  
  public IntegratedMovement(String n) {
    super(n);
  }

  
  public void changeArm(String arm) {
    if (arms.containsKey(arm)) {
      currentArm = arms.get(arm);
      //currentServos = servos.get(arm);
    }
    else {
      log.info("IK service have no data for {}", arm);
    }
    
  }

  public Point currentPosition() {
    return currentArm.getPalmPosition();
  }

  public Point currentPosition(String arm) {
    if (arms.containsKey(arm)) {
      return arms.get(arm).getPalmPosition();
    }
    log.info("IK service have no data for {}", arm);
    return new Point(0, 0, 0, 0, 0, 0);
  }
  
  public void moveTo(double x, double y, double z) {
    // TODO: allow passing roll pitch and yaw
    moveTo(new Point(x, y, z, 0, 0, 0));
  }

  public void moveTo(String arm, double x, double y, double z) {
    moveTo(arm, new Point(x, y, z, 0, 0, 0));
  }
  /**
   * This create a rotation and translation matrix that will be applied on the
   * "moveTo" call.
   * 
   * @param dx
   *          - x axis translation
   * @param dy
   *          - y axis translation
   * @param dz
   *          - z axis translation
   * @param roll
   *          - rotation about z (in degrees)
   * @param pitch
   *          - rotation about x (in degrees)
   * @param yaw
   *          - rotation about y (in degrees)
   * @return
   */
  public Matrix createInputMatrix(double dx, double dy, double dz, double roll, double pitch, double yaw) {
    roll = MathUtils.degToRad(roll);
    pitch = MathUtils.degToRad(pitch);
    yaw = MathUtils.degToRad(yaw);
    Matrix trMatrix = Matrix.translation(dx, dy, dz);
    Matrix rotMatrix = Matrix.zRotation(roll).multiply(Matrix.yRotation(yaw).multiply(Matrix.xRotation(pitch)));
    inputMatrix = trMatrix.multiply(rotMatrix);
    return inputMatrix;
  }

  public Point rotateAndTranslate(Point pIn) {

    Matrix m = new Matrix(4, 1);
    m.elements[0][0] = pIn.getX();
    m.elements[1][0] = pIn.getY();
    m.elements[2][0] = pIn.getZ();
    m.elements[3][0] = 1;
    Matrix pOM = inputMatrix.multiply(m);

    // TODO: compute the roll pitch yaw
    double roll = 0;
    double pitch = 0;
    double yaw = 0;

    Point pOut = new Point(pOM.elements[0][0], pOM.elements[1][0], pOM.elements[2][0], roll, pitch, yaw);
    return pOut;
  }

  public void moveTo(Point p) {

    // log.info("Move TO {}", p );
    if (inputMatrix != null) {
      p = rotateAndTranslate(p);
    }
    boolean success = false;
    if(computeMethod == IK_COMPUTE_METHOD_PI_JACOBIAN) {
      success = currentArm.moveToGoal(p);
    }
    else if (computeMethod == IK_COMPUTE_METHOD_GENETIC_ALGORYTHM) {
      goTo = p;
      GeneticAlgorithm GA = new GeneticAlgorithm(this, geneticPoolSize, currentArm.getNumLinks(), 11, geneticRecombinationRate, geneticMutationRate);
      //HashMap<Integer,Integer> lastIteration = new HashMap<Integer,Integer>();
      int retry = 0;
      //stopMoving = false;
      while (retry++ < 100) {
      	if (stopMoving) {		
      		//stopMoving = false;		
      		break;		
      	}		
        Chromosome bestFit = GA.doGeneration(geneticGeneration); // this is the number of time the chromosome pool will be recombined and mutate
        //DHRobotArm checkedArm = simulateMove(bestFit.getDecodedGenome());
        currentArm = simulateMove(bestFit.getDecodedGenome());
        for (int i = 0; i < currentArm.getNumLinks(); i++){
          Servo servo = currentServos.get(currentArm.getLink(i).getName());
          while (servo.isMoving()){
          	sleep(10);
          }
        }
        for (int i = 0; i < currentArm.getNumLinks(); i++){
          Servo servo = currentServos.get(currentArm.getLink(i).getName());
          servo.moveTo(currentArm.getLink(i).getPositionValueDeg());
        }
        this.
        log.info("moving to {}", currentPosition());
        if (collisionItems.haveCollision()) {
          //collision avoiding need to be improved
          CollisionItem ci = null;
          int itemIndex = 0;
          for (DHLink l : currentArm.getLinks()) {
        	boolean foundIt = false;
            for (itemIndex = 0; itemIndex < 2; itemIndex++) {
              if (l.getName().equals(collisionItems.getCollisionItem()[itemIndex].getName())) {
                ci = collisionItems.getCollisionItem()[itemIndex];
                foundIt = true;
                break;
              }
            }
            if (foundIt) break; //we have the item to watch
          }
          if (ci == null) {
            log.info("Collision between static item {} and {} detected", collisionItems.getCollisionItem()[0].getName(), collisionItems.getCollisionItem()[1].getName());
            break; //collision is between items that we can't control
          }
          //current implementation of the collision avoidance is not very good
          //need to calculate a vector away from the object and a vector to avoid the object and move that way
          //current implementation add a bias toward outside position
          Point newPos = currentPosition();
          newPos.setX(newPos.getX()+collisionItems.getCollisionPoint()[itemIndex].getX()-collisionItems.getCollisionPoint()[1-itemIndex].getX());
          newPos.setY(newPos.getY()+collisionItems.getCollisionPoint()[itemIndex].getY()-collisionItems.getCollisionPoint()[1-itemIndex].getY());
          newPos.setZ(newPos.getZ()+collisionItems.getCollisionPoint()[itemIndex].getZ()-collisionItems.getCollisionPoint()[1-itemIndex].getZ());
          Point ori=collisionItems.getCollisionItem()[1-itemIndex].getOrigin();
          Point end=collisionItems.getCollisionItem()[1-itemIndex].getEnd();
          Point colPoint = collisionItems.getCollisionPoint()[1-itemIndex];
          if (collisionItems.getCollisionLocation()[1-itemIndex] > 0.0 || collisionItems.getCollisionLocation()[1-itemIndex] < 1.0) { // collision on the side of item
          	if (collisionItems.getCollisionLocation()[1-itemIndex] < 0.5) { //collision near the origin
              newPos.setX(newPos.getX()+ori.getX()-colPoint.getX());
              newPos.setY(newPos.getY()+ori.getY()-colPoint.getY());
              newPos.setZ(newPos.getZ()+ori.getZ()-colPoint.getZ());
          	}
          	else { //collision near the end
              newPos.setX(newPos.getX()+end.getX()-colPoint.getX());
              newPos.setY(newPos.getY()+end.getY()-colPoint.getY());
              newPos.setZ(newPos.getZ()+end.getZ()-colPoint.getZ());
          	}
          }
          //move away by the radius of the part
          double length = collisionItems.getCollisionItem()[1-itemIndex].getLength();
          double ratio = collisionItems.getCollisionItem()[itemIndex].getRadius() / length;
          double[] vector = collisionItems.getCollisionItem()[1-itemIndex].getVector();
          for (int i=0; i<3; i++){
          	vector[i] *= ratio;
          }
        	if (collisionItems.getCollisionLocation()[1-itemIndex] < 0.5) { //collision near the origin
            newPos.setX(newPos.getX() - vector[0]);
            newPos.setY(newPos.getY() - vector[1]);
            newPos.setZ(newPos.getZ() - vector[2]);
        	}
        	else {
            newPos.setX(newPos.getX() + vector[0]);
            newPos.setY(newPos.getY() + vector[1]);
            newPos.setZ(newPos.getZ() + vector[2]);
        	}
        	this.outbox.notify();
          Point oldGoTo = goTo;
          if(!stopMoving) moveTo(newPos);
          goTo = oldGoTo;
          if (moveInfo != null) {		
          	goTo = moveToObject();		
          }		
        }
        else break;
        
      } 
    }
    if (success) {
      publishTelemetry();
    }
  }
  
  public void moveTo(String arm, Point p) {
    changeArm(arm);
    moveTo(p);
  }

  public void publishTelemetry() {
    Map<String, Double> angleMap = new HashMap<String, Double>();
    for (DHLink l : currentArm.getLinks()) {
      String jointName = l.getName();
      double theta = l.getTheta();
      // angles between 0 - 360 degrees.. not sure what people will really want?
      // - 180 to + 180 ?
      angleMap.put(jointName, (double) MathUtils.radToDeg(theta) % 360.0F);
    }
    invoke("publishJointAngles", angleMap);
    // we want to publish the joint positions
    // this way we can render on the web gui..
    double[][] jointPositionMap = createJointPositionMap();
    // TODO: pass a better datastructure?
    invoke("publishJointPositions", (Object) jointPositionMap);
  }
  
  public double[][] createJointPositionMap() {
    return createJointPositionMap(currentArm);
  }
  
  public double[][] createJointPositionMap(String arm) {
    changeArm(arm);
    return createJointPositionMap(currentArm);
  }
  

  public double[][] createJointPositionMap(DHRobotArm arm) {

    double[][] jointPositionMap = new double[arm.getNumLinks() + 1][3];

    // first position is the origin... second is the end of the first link
    jointPositionMap[0][0] = 0;
    jointPositionMap[0][1] = 0;
    jointPositionMap[0][2] = 0;

    for (int i = 1; i <= arm.getNumLinks(); i++) {
      Point jp = arm.getJointPosition(i - 1);
      jointPositionMap[i][0] = jp.getX();
      jointPositionMap[i][1] = jp.getY();
      jointPositionMap[i][2] = jp.getZ();
    }
    return jointPositionMap;
  }

  public DHRobotArm getCurrentArm() {
    return currentArm;
  }
  
  public DHRobotArm getArm(String arm) {
    if (arms.containsKey(arm)) {
      return arms.get(arm);
    }
    else return currentArm;
  }

  public void setCurrentArm(DHRobotArm currentArm) {
    this.currentArm = currentArm;
  }
  
  public void setCurrentArm(String name) {
  	if (arms.get(name) != null) {
  		this.currentArm = arms.get(name);
  	}
  	else {
  		log.info("Arm do not exist");
  	}
  }
  
  public void addArm(String name, DHRobotArm currentArm) {
    arms.put(name, currentArm);
    this.currentArm = currentArm;
  }

  public static void main(String[] args) throws Exception {
    LoggingFactory.init(Level.INFO);

    Runtime.createAndStart("python", "Python");
    Runtime.createAndStart("gui", "SwingGui");
    IntegratedMovement ik = (IntegratedMovement) Runtime.start("ik", "IntegratedMovement");
    Arduino arduino = (Arduino) Runtime.start("arduino", "Arduino");
    arduino.connect("COM22");
    arduino.enableBoardInfo(false);
    //define and attach servo
    //map is set so servo accept angle as input, output where
    //they need to go so that their part they where attach to
    //move by the input degree
    Servo mtorso = (Servo)Runtime.start("mtorso","Servo");
    mtorso.attach(arduino,26,90);
    mtorso.map(15,165,148,38);
    //#mtorso.setMinMax(35,150);
    mtorso.setVelocity(13);
    mtorso.moveTo(90);
    Servo ttorso = (Servo) Runtime.start("ttorso","Servo");
    ttorso.attach(arduino,7,90);
    ttorso.map(80,100,92,118);
    //ttorso.setInverted(False)
    //#ttorso.setMinMax(85,125)
    ttorso.setVelocity(13);
    ttorso.moveTo(90);
    Servo omoplate = (Servo) Runtime.start("omoplate","Servo");
    omoplate.attach(arduino,11,10);
    omoplate.map(10,70,10,70);
    omoplate.setVelocity(15);
    //#omoplate.setMinMax(10,70)
    omoplate.moveTo(10);
    Servo shoulder = (Servo) Runtime.start("shoulder","Servo");
    shoulder.attach(arduino,6,30);
    shoulder.map(0,180,0,180);
    //#shoulder.setMinMax(0,180)
    shoulder.setVelocity(14);
    shoulder.moveTo(20);
    Servo rotate = (Servo) Runtime.start("rotate","Servo");
    rotate.attach(arduino,9,90);
    rotate.map(46,160,46,160);
    //#rotate.setMinMax(46,180)
    rotate.setVelocity(18);
    rotate.moveTo(90);
    Servo bicep = (Servo) Runtime.start("bicep","Servo");
    bicep.attach(arduino,8,10);
    bicep.map(5,60,5,80);
    bicep.setVelocity(26);
    //#bicep.setMinMax(5,90)
    bicep.moveTo(10);
    Servo wrist = (Servo) Runtime.start("wrist","Servo");
    wrist.attach(arduino,7,10);
    //#wrist.map(45,135,45,135)
    wrist.map(90,90,90,90);
    wrist.setVelocity(26);
    //#bicep.setMinMax(5,90)
    wrist.moveTo(90);
    Servo finger = (Servo) Runtime.start("finger","Servo");
    finger.attach(arduino,8,90);
    finger.map(90,90,90,90);
    finger.setVelocity(26);
    //#bicep.setMinMax(5,90)
    finger.moveTo(90);

    //#define the DH parameters for the ik service
    ik.setNewDHRobotArm("leftArm");
    ik.setDHLink(mtorso,113,90,0,-90);
    ik.setDHLink(ttorso,0,90+65.6,346,0);
    ik.setDHLink(omoplate,0,-5.6+24.4+180,55,-90);
    ik.setDHLink(shoulder,77,-20+90,0,90);
    ik.setDHLink(rotate,284,90,40,90);
    ik.setDHLink(bicep,0,-7+24.4+90,300,90);
    //#ik.setDHLink(wrist,00,-90,200,0)
    ik.setDHLink(wrist,00,-90,100,-90);
    //print ik.currentPosition();

    ik.setDHLink(finger,00,00,300,0);

    ik.setNewDHRobotArm("kinect");
    ik.setDHLink(mtorso,113,90,0,-90);
    ik.setDHLink(ttorso,0,90+90,110,-90);
    ik.setDHLink("camera",0,90,10,90);

    //#define object, each dh link are set as an object, but the
    //#start point and end point will be update by the ik service, but still need
    //#a name and a radius
    //#static object need a start point, an end point, a name and a radius 
    ik.clearObject();
    ik.addObject(0.0, 0.0, 0.0, 0.0, 0.0, -150.0, "base", 150.0);
    ik.addObject("mtorso", 150.0);
    ik.addObject("ttorso", 10.0);
    ik.addObject("omoplate", 10.0);
    ik.addObject("shoulder", 50.0);
    ik.addObject("rotate", 50.0);
    ik.addObject("bicep", 60.0);
    ik.addObject("wrist", 70.0);
    ik.addObject("finger",10.0);
    //#ik.addObject(-1000.0, 300, 0, 1000, 300, 00, "obstacle",40)
    //#ik.addObject(360,540,117,360, 550,107,"cymbal",200)
    //#ik.addObject(90,530,-180,300,545,-181,"bell", 25)
    //#ik.addObject(-170,640,-70,-170,720,-250,"tom",150)


    //print ik.currentPosition();



    //#setting ik parameters for the computing
    ik.setComputeMethodGeneticAlgorythm();
    ik.setGeneticComputeSimulation(false);

    //#move to a position
    ik.moveTo("leftArm",260,410,-120);
    //ik.moveTo(280,190,-345);
    //#ik.moveTo("cymbal",ik.ObjectPointLocation.ORIGIN_SIDE, 0,0,5)
    //#mtorso.moveTo(45)
    //print ik.currentPosition("leftArm")

    //print "kinect Position" + str(ik.currentPosition("kinect"));

    ik.startOpenNI();
    
    ik.processKinectData();

  }

  @Override
  public Map<String, Double> publishJointAngles(HashMap<String, Double> angleMap) {
    return angleMap;
  }

  public double[][] publishJointPositions(double[][] jointPositionMap) {
    return jointPositionMap;
  }

  public Point publishTracking(Point tracking) {
    return tracking;
  }

  @Override
  public void onPoints(List<Point> points) {
    // TODO : move input matrix translation to here? or somewhere?
    // TODO: also don't like that i'm going to just say take the first point
    // now.
    // TODO: points should probably be a map, each point should have a name ?
    moveTo(points.get(0));
  }


  /**
   * This static method returns all the details of the class without it having
   * to be constructed. It has description, categories, dependencies, and peer
   * definitions.
   * 
   * @return ServiceType - returns all the data
   * 
   */
  static public ServiceType getMetaData() {

    ServiceType meta = new ServiceType(InverseKinematics3D.class.getCanonicalName());
    meta.addDescription("a 3D kinematics service supporting D-H parameters");
    meta.addCategory("robot", "control");
    meta.addPeer("openni", "OpenNi", "Kinect service");

    return meta;
  }

  public void setDHLink (String name, double d, double theta, double r, double alpha) {
    DHLink dhLink = new DHLink(name, d, r, MathUtils.degToRad(theta), MathUtils.degToRad(alpha));
    currentArm.addLink(dhLink);
    arms.put(currentArm.name, currentArm);
  }
  
  public void setDHLink (Servo servo, double d, double theta, double r, double alpha) {
    DHLink dhLink = new DHLink(servo.getName(), d, r, MathUtils.degToRad(theta), MathUtils.degToRad(alpha));
    servo.addIKServoEventListener(this);
    currentServos.put(servo.getName(), servo);
    dhLink.addPositionValue(servo.getPos());
    dhLink.setMin(MathUtils.degToRad(theta + Math.min(servo.getMin(), servo.getMax())));
    dhLink.setMax(MathUtils.degToRad(theta + Math.max(servo.getMax(), servo.getMin())));
    currentArm.addLink(dhLink);
    arms.put(currentArm.name, currentArm);
  }
  
  public void setDHLink (String armName, String name, double d, double theta, double r, double alpha) {
    changeArm(armName);
    setDHLink(name, d, theta, r, alpha);
  }  
  
  public void setDHLink (String armName, Servo servo, double d, double theta, double r, double alpha) {
    changeArm(armName);
    setDHLink(servo, d, theta, r, alpha);
    arms.put(currentArm.name, currentArm);
  }  
  
  public void setNewDHRobotArm(String name) {
    currentArm = new DHRobotArm();
    currentArm.name = name;
  	arms.put(name, currentArm);
  }
  
  public void moveTo(int x , int y, int z, int roll, int pitch, int yaw) {
    moveTo(new Point(x, y, z, roll, pitch, yaw));
  }
  
  public void moveTo(int x, int y, int z) {
    Point goTo = new Point((double)x,(double)y,(double)z,0.0,0.0,0.0);
    moveTo(goTo);
  }

  public void moveTo(String arm, int x, int y, int z) {
    changeArm(arm);
    moveTo(x, y, z);
  }
  
  @Override
  public void calcFitness(ArrayList<Chromosome> pool) {
    for (Chromosome chromosome : pool) {
      DHRobotArm arm = new DHRobotArm();
      double fitnessMult = 1;
      double fitnessTime = 0;
      for (int i = 0; i < currentArm.getNumLinks(); i++){
        //copy the value of the currentArm
        DHLink newLink = new DHLink(currentArm.getLink(i));
        if (chromosome.getDecodedGenome() != null) {
	        newLink.addPositionValue((double)chromosome.getDecodedGenome().get(i));
	        Double delta = currentArm.getLink(i).getPositionValueDeg() - (Double)chromosome.getDecodedGenome().get(i);
	        double timeOfMove = Math.abs(delta / currentServos.get(currentArm.getLink(i).getName()).getVelocity());
	        if (timeOfMove > fitnessTime) {
	          fitnessTime = timeOfMove;
	        }
        }
        arm.addLink(newLink);
      }
      if (geneticComputeSimulation) {
        //work well but long computing time
        arm = simulateMove(chromosome.getDecodedGenome());
      }
      Point potLocation = arm.getPalmPosition();
      Double distance = potLocation.distanceTo(goTo);
      //not sure about weight for roll/pitch/yaw. adding a wrist will probably help
//      double dRoll = (potLocation.getRoll() - goTo.getRoll())/360;
//      fitnessMult*=(1-dRoll)*10000;
//      double dPitch = (potLocation.getPitch() - goTo.getPitch())/360;
//      fitnessMult*=(1-dPitch)*10000;
//      double dYaw = (potLocation.getYaw() - goTo.getYaw())/360;
//      fitnessMult*=(1-dYaw)*10000;
      if (fitnessTime < 0.1) {
        fitnessTime = 0.1;
      }
      //fitness is the score showing how close the results is to the target position
      Double fitness = (fitnessMult/distance*1000);// + (1/fitnessTime*.01);
      if (fitness < 0) fitness *=-1;
      chromosome.setFitness(fitness);
    }
    return;
  }
  // convert the genetic algorythm to the data we want to use
  @Override
  public void decode(ArrayList<Chromosome> chromosomes) {
    for (Chromosome chromosome : chromosomes ){
      int pos=0;
      ArrayList<Object>decodedGenome = new ArrayList<Object>();
      for (DHLink link: currentArm.getLinks()){
      	Servo servo = currentServos.get(link.getName());
      	if (servo == null) {
      		decodedGenome.add(null);
      		continue;
      	}
      	
      	Mapper map = null;
      	if(servo.getMin() == servo.getMax()) {
      		decodedGenome.add(servo.getMin());
      		continue;
      	}
      	else {
      		map = new Mapper(0,2047,servo.getMin(),servo.getMax());
      	}
        Double value=0.0;
        for (int i= pos; i< chromosome.getGenome().length() && i < pos+11; i++){
          if(chromosome.getGenome().charAt(i) == '1') value += 1 << i-pos; 
        }
        pos += 11;
        value = map.calcOutput(value);
        if (value.isNaN()) {
        	value = link.getPositionValueDeg();
        }
        //if (value < MathUtils.radToDeg(link.getMin()-link.getInitialTheta())) value = link.getPositionValueDeg();
        //if (value > MathUtils.radToDeg(link.getMax()-link.getInitialTheta())) value = link.getPositionValueDeg();
        decodedGenome.add(value);
      }
      chromosome.setDecodedGenome(decodedGenome);
    }
  }
  private DHRobotArm simulateMove(ArrayList<Object> decodedGenome) {
    // simulate movement of the servos in time to get an approximation of their position
    time = 0.1;
    boolean isMoving = true;
    DHRobotArm oldArm = currentArm;
    // stop simulating when all servo reach position
    while (isMoving) {
      isMoving = false;
      DHRobotArm newArm = new DHRobotArm();
      newArm.name = currentArm.name;
      for (int i = 0; i < currentArm.getNumLinks(); i++) {
        DHLink newLink = new DHLink(currentArm.getLink(i));
        double degrees = currentArm.getLink(i).getPositionValueDeg();
        double deltaDegree = java.lang.Math.abs(degrees - (Double)decodedGenome.get(i));
        double deltaDegree2 = time * currentServos.get(currentArm.getLink(i).getName()).getVelocity();
        if (deltaDegree >= deltaDegree2) {
          deltaDegree = deltaDegree2;
          isMoving = true;
        }
        if (degrees > ((Double)decodedGenome.get(i)).intValue()) {
          degrees -= deltaDegree;
        }
        else if (degrees < ((Double)decodedGenome.get(i)).intValue()) {
          degrees += deltaDegree;
        }
        newLink.addPositionValue( degrees);
        newArm.addLink(newLink);
      }
      double[][] jp = createJointPositionMap(newArm);
      //send data to the collision detector class
      for (int i = 0; i < currentArm.getNumLinks(); i++) {
        CollisionItem ci = new CollisionItem(new Point(jp[i][0], jp[i][1], jp[i][2], 0 , 0, 0), new Point(jp[i+1][0], jp[i+1][1], jp[i+1][2], 0, 0, 0), currentArm.getLink(i).getName());
        if (i != currentArm.getNumLinks()-1) {
          ci.addIgnore(currentArm.getLink(i+1).getName());
        }
        collisionItems.addItem(ci);
      }
      collisionItems.runTest();
      if (collisionItems.haveCollision() ){
        //log.info("Collision at {} - {}", collisionItems.getCollisionPoint()[0], collisionItems.getCollisionPoint()[1]);
        return oldArm;
      }
      oldArm = newArm;
      //log.info("time: {} Position:{}", ((Double)time).floatValue(), newArm.getPalmPosition().toString());
      //log.info("collision: {}", collisionItems.haveCollision());
      for (int i = 1; i < jp.length;  i++){
        //log.info("jp:{} {} - {} - {}", newArm.getLink(i-1).getName(), ((Double)jp[i][0]).intValue(), ((Double)jp[i][1]).intValue(), ((Double)jp[i][2]).intValue());
      }
      time += 0.1;
    }
    return oldArm;
  }
  
  public String addObject(double oX, double oY, double oZ, double eX, double eY, double eZ, String name, double radius) {
    return addObject(new Point(oX, oY, oZ, 0, 0, 0), new Point(eX, eY, eZ, 0, 0, 0), name, radius);
  }

  public String addObject(Point origin, Point end, String name, double radius) {
    CollisionItem item = new CollisionItem(origin, end, name, radius);
    collisionItems.addItem(item);
    return item.getName();
  }
  public String addObject(String name, double radius) {
    return addObject(new Point(0, 0, 0, 0, 0, 0), new Point(0, 0, 0, 0, 0, 0), name, radius);
  }
  
  public String addObject(HashMap<Integer[],Map3DPoint> cloudMap) {
  	CollisionItem item = new CollisionItem(cloudMap);
  	collisionItems.addItem(item);
  	return item.getName();
  }
  
  public void clearObject(){
    collisionItems.clearItem();
  }
  
  public void setComputeMethodPSIJacobian() {
    computeMethod = IK_COMPUTE_METHOD_PI_JACOBIAN;
  }
  
  public void setComputeMethodGeneticAlgorythm() {
    computeMethod = IK_COMPUTE_METHOD_GENETIC_ALGORYTHM;
  }
  
  public void setGeneticPoolSize(int size) {
    geneticPoolSize = size;
  }
  
  public void setGeneticMutationRate(double rate) {
    geneticMutationRate = rate;
  }
  
  public void setGeneticRecombinationRate(double rate) {
    geneticRecombinationRate = rate;
  }
  
  public void setGeneticGeneration(int generation) {
    geneticGeneration = generation;
  }
  
  public void setGeneticComputeSimulation(boolean compute) {
    geneticComputeSimulation = compute;
  }
  
  public void objectAddIgnore(String object1, String object2) {
    collisionItems.addIgnore(object1, object2);
  }
  
  public void onIKServoEvent(IKData data) {
  	for (DHRobotArm a : arms.values()) {
	    for (DHLink l: a.getLinks()) {
	      if (l.getName().equals(data.name)){
	        l.addPositionValue(data.pos);
	      }
	    }
    }
    if (openni != null) {
    	map3d.updateKinectPosition(currentPosition(kinectName));
    }
    if (jmeApp != null) {
    	jmeApp.updateObjects(collisionItems.getItems());
    }
  }
  
  public void moveTo(String name, ObjectPointLocation location, int xoffset, int yoffset, int zoffset) {		
  	stopMoving = false;
  	moveInfo = new MoveInfo();		
  	moveInfo.offset = new Point(xoffset, yoffset, zoffset, 0, 0, 0);		
  	moveInfo.targetItem = collisionItems.getItem(name);		
  	moveInfo.objectLocation = location;		
  	if (moveInfo.targetItem == null){		
  		log.info("no items named {} found",name);		
  		moveInfo = null;		
  		return;		
  	}		
  	moveTo(moveToObject());		
  }		
  		
  private Point moveToObject() {		
  	Point[] point = new Point[2];		
  	moveInfo.lastLink = currentArm.getLink(currentArm.getNumLinks()-1);		
  	CollisionItem lastLinkItem = collisionItems.getItem(moveInfo.lastLink.getName());		
		Double[] vector = new Double[3];		
		boolean addRadius=false;		
  	switch (moveInfo.objectLocation) {		
  		case ORIGIN_CENTER: {		
  			point[0] = moveInfo.targetItem.getOrigin();		
  			break;		
  		}		
  		case END_CENTER: {		
  			point[0] = moveInfo.targetItem.getEnd();		
  			break;		
  		}		
  		case CLOSEST_POINT: {		
  			point = collisionItems.getClosestPoint(moveInfo.targetItem, lastLinkItem, new Double[2], vector);		
  			addRadius = true;		
  			break;		
  		}		
  		case ORIGIN_SIDE: {		
  			point[0] = moveInfo.targetItem.getOrigin();		
  			addRadius = true;		
  			break;		
  		}		
  		case END_SIDE: {		
  			point[0] = moveInfo.targetItem.getEnd();		
  			addRadius = true;		
  			break;		
  		}		
  		case CENTER_SIDE: {		
  			point = collisionItems.getClosestPoint(moveInfo.targetItem, lastLinkItem, new Double[]{0.5, 0.5}, vector);		
  			addRadius = true;		
  		}		
  		case CENTER: {		
  			point = collisionItems.getClosestPoint(moveInfo.targetItem, lastLinkItem, new Double[]{0.5, 0.5}, vector);		
  		}		
  	}		
  	if(addRadius) {		
  		double[] vectori = moveInfo.targetItem.getVector();		
  		double[] vectorT = moveInfo.targetItem.getVectorT();		
  		Point side0 = new Point(point[0].getX()+vectorT[0], point[0].getY()+vectorT[1], point[0].getZ()+vectorT[2], 0, 0, 0);		
  		Point pointF = side0;		
  		Point curPos = currentPosition();		
  		double d = Math.pow((side0.getX() - curPos.getX()),2) + Math.pow((side0.getY() - curPos.getY()),2) + Math.pow((side0.getZ() - curPos.getZ()),2);		
  		for (int i = 0; i < 360; i+=10) {		
  			double L = vectori[0]*vectori[0] + vectori[1]*vectori[1] + vectori[2]*vectori[2];		
  			double x = ((moveInfo.targetItem.getOrigin().getX()*(Math.pow(vectori[1],2)+Math.pow(vectori[2], 2)) - vectori[0] * (moveInfo.targetItem.getOrigin().getY()*vectori[1] + moveInfo.targetItem.getOrigin().getZ()*vectori[2] - vectori[0]*side0.getX() - vectori[1]*side0.getY() - vectori[2]*side0.getZ())) * (1 - Math.cos(MathUtils.degToRad(i))) + L * side0.getX() * Math.cos(MathUtils.degToRad(i)) + Math.sqrt(L) * (-moveInfo.targetItem.getOrigin().getZ()*vectori[1] + moveInfo.targetItem.getOrigin().getY()*vectori[2] - vectori[2]*side0.getY() + vectori[1]*side0.getZ()) * Math.sin(MathUtils.degToRad(i))) / L;		
  			double y = ((moveInfo.targetItem.getOrigin().getY()*(Math.pow(vectori[0],2)+Math.pow(vectori[2], 2)) - vectori[1] * (moveInfo.targetItem.getOrigin().getX()*vectori[0] + moveInfo.targetItem.getOrigin().getZ()*vectori[2] - vectori[0]*side0.getX() - vectori[1]*side0.getY() - vectori[2]*side0.getZ())) * (1 - Math.cos(MathUtils.degToRad(i))) + L * side0.getY() * Math.cos(MathUtils.degToRad(i)) + Math.sqrt(L) * ( moveInfo.targetItem.getOrigin().getZ()*vectori[0] - moveInfo.targetItem.getOrigin().getX()*vectori[2] + vectori[2]*side0.getX() - vectori[0]*side0.getZ()) * Math.sin(MathUtils.degToRad(i))) / L;		
  			double z = ((moveInfo.targetItem.getOrigin().getZ()*(Math.pow(vectori[0],2)+Math.pow(vectori[1], 2)) - vectori[2] * (moveInfo.targetItem.getOrigin().getX()*vectori[0] + moveInfo.targetItem.getOrigin().getY()*vectori[1] - vectori[0]*side0.getX() - vectori[1]*side0.getY() - vectori[2]*side0.getZ())) * (1 - Math.cos(MathUtils.degToRad(i))) + L * side0.getZ() * Math.cos(MathUtils.degToRad(i)) + Math.sqrt(L) * (-moveInfo.targetItem.getOrigin().getY()*vectori[0] + moveInfo.targetItem.getOrigin().getX()*vectori[1] - vectori[1]*side0.getX() + vectori[0]*side0.getY()) * Math.sin(MathUtils.degToRad(i))) / L;		
  			Point check = new Point(x,y,z,0,0,0);		
    		double dt = Math.pow((check.getX() - curPos.getX()),2) + Math.pow((check.getY() - curPos.getY()),2) + Math.pow((check.getZ() - curPos.getZ()),2);		
    		if (dt < d) {		
    			pointF = check;		
    			d = dt;		
    		}		
  		}		
  		point[0] = pointF;		
  	}		
  	Point moveToPoint = point[0].add(moveInfo.offset);		
  	log.info("Moving to point {}", moveToPoint);		
  	return moveToPoint;		
  }		
  
	public void stopMoving() {		
  	stopMoving = true;		
  }		
  		
  public OpenNi startOpenNI() throws Exception {
    if (openni == null) {
      openni = (OpenNi) startPeer("openni");
      openni.start3DData();
      map3d.updateKinectPosition(currentPosition(kinectName));
      //this.subscribe(openni.getName(), "publishOpenNIData", this.getName(), "onOpenNiData");
    }
    return openni;
  }
  
  public void onOpenNiData(OpenNiData data){
		if (ProcessKinectData) {
			ProcessKinectData = false;
  		long a = System.currentTimeMillis();
  		log.info("start {}",a);
  		map3d.processDepthMap(data);
  		removeKinectObject();
  		ArrayList<HashMap<Integer[],Map3DPoint>> object = map3d.getObject();
  		for (int i = 0; i < object.size(); i++) {
  			addObject(object.get(i));
  		}
  		long b = System.currentTimeMillis();
  		log.info("end {} - {} - {}",b, b-a, this.inbox.size());
  		broadcastState();
		}
  }

  private void removeKinectObject() {
		collisionItems.removeKinectObject();
		
	}


	public void processKinectData(){
  	ProcessKinectData = true;
  	onOpenNiData(openni.get3DData());
  }
  
  public void setKinectName(String kinectName) {
  	this.kinectName = kinectName;
  }


	public HashMap<String, CollisionItem> getCollisionObject() {
		return collisionItems.getItems();
	}


	public ObjectPointLocation[] getEnumLocationValue() {
		return ObjectPointLocation.values();
	}
	
	public Collection<DHRobotArm> getArms() {
		return this.arms.values();
	}
	
	public void visualize() {
		jmeApp = new TestJmeIntegratedMovement();
		jmeApp.setObjects(getCollisionObject());
		jmeApp.start();

	}
}
