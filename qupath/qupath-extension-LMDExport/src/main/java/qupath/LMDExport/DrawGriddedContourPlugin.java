package qupath.LMDExport;

import edu.mines.jtk.dsp.Sampling;
import edu.mines.jtk.interp.SibsonGridder2;
import edu.mines.jtk.mosaic.ContoursView;
import edu.mines.jtk.mosaic.PlotPanel;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.geom.Point3;
import qupath.lib.gui.helpers.MeasurementMapper;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.*;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.plugins.AbstractPlugin;
import qupath.lib.plugins.PathPlugin;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.plugins.workflow.SimplePluginWorkflowStep;
import qupath.lib.plugins.workflow.WorkflowStep;
import qupath.lib.roi.*;
import qupath.lib.rois.vertices.Vertices;
import sun.nio.cs.ext.MS50220;

import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;

/**
 * Created by cschlosser on 08/05/2018.
 * This plugin draws contours around areas which contain objects with measurements of certain values
 * The contour drawing relies on "gridding" algorithms which converts randomly scattered data into a regularly spaced matrix such that contour lines can be calculated.
 * The plugin gives the user a mapping tool to visualize the contours before they are drawn
 * Becasue the gridding algorithms are slow, and because QuPath is somewhat slow at updating hiearchies with 100,000s or 1,000,000s of objects, this plugin takes a considerable amount of time to run.
 */
public class DrawGriddedContourPlugin extends AbstractPlugin<BufferedImage>{

    private String selectedMeasurement;
    private double minAreaFilter;
    private double lowerThresholdValue;
    private double upperThresholdValue;
    private boolean thresholdHigh;
    private String lastMessage = null;
    private boolean keepHoles;

    public DrawGriddedContourPlugin() {
        this.selectedMeasurement = null;
        this.minAreaFilter = 0.0;
        this.lowerThresholdValue = 0.0;
        this.upperThresholdValue = 0.0;
        this.thresholdHigh = false;
        this.keepHoles = true;
    }

    public DrawGriddedContourPlugin(final String selectedMeasurement, final double minAreaFilter, final double lowerThresholdValue, final double upperThresholdValue, final boolean thresholdHigh, final boolean keepHoles) {
        this.selectedMeasurement = selectedMeasurement;
        this.minAreaFilter = minAreaFilter;
        this.lowerThresholdValue = lowerThresholdValue;
        this.upperThresholdValue = upperThresholdValue;
        this.thresholdHigh = thresholdHigh;
        this.keepHoles = keepHoles;
    }


//    @Override
//    protected void addWorkflowStep(final ImageData<BufferedImage> imageData, final String arg) {
//        WorkflowStep step = new SimplePluginWorkflowStep(getName(), (Class<? extends PathPlugin<T>>)getClass(), arg);
//        imageData.getHistoryWorkflow().addStep(step);
//        logger.info("{}", step);
//    }

    @Override
    public String getName() {
        return "Draw Contour";
    }

    @Override
    public String getDescription() {
        return "Draw Contours at isolines of selected measurement";
    }

    @Override
    public String getLastResultsDescription() {
        return lastMessage;
    }

    @Override
    protected boolean parseArgument(ImageData<BufferedImage> imageData, String arg) {

        return true;
    }

    public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
        return Arrays.asList(
                PathAnnotationObject.class,
                TMACoreObject.class
        );
    }

    @Override
    protected Collection<? extends PathObject> getParentObjects(PluginRunner<BufferedImage> runner) {
        Collection<Class<? extends PathObject>> supported = getSupportedParentObjectClasses();
        Collection<PathObject> selectedObjects = runner
                .getHierarchy()
                .getSelectionModel()
                .getSelectedObjects();
        Collection<? extends PathObject> objects = PathObjectTools.getSupportedObjects(selectedObjects, supported);
        // In the event that not all selected objects were chosen, we need to deselect the others
        if (!objects.isEmpty() && selectedObjects.size() > objects.size()) {
            Set<PathObject> objectsToDeselect = new HashSet<>(selectedObjects);
            objectsToDeselect.removeAll(objects);
            runner.getHierarchy().getSelectionModel().deselectObjects(objectsToDeselect);
        }
        return objects;

    }


    @Override
    protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
        Runnable runnable = new Runnable() {

            @Override
            public void run() {

                ImageServer server =  imageData.getServer();
                ArrayList<PathObject> measurementPathObjects = new ArrayList<>();
                imageData.getHierarchy().getDescendantObjects(parentObject,measurementPathObjects, PathDetectionObject.class);
                Predicate<PathObject> noMeasurement = p -> !p.getMeasurementList().containsNamedMeasurement(selectedMeasurement);
                Predicate<PathObject> nanMeasurement = p-> Double.isNaN(p.getMeasurementList().getMeasurementValue(selectedMeasurement));
                measurementPathObjects.removeIf(noMeasurement);
                measurementPathObjects.removeIf(nanMeasurement);

                if( !(measurementPathObjects.size()>0) )
                {
                    lastMessage = "No objects in selected annotation have the selected measurement";
                    return;
                }

                MeasurementMapper mapper = new MeasurementMapper(selectedMeasurement, measurementPathObjects);


                //Threshold high/low refers to whether you want to capture areas in (min, threshlevel) or in (threshlevel, max)
                float contourLevel;
                boolean invertContours = false;
                if(thresholdHigh) {
                    contourLevel  = (float)upperThresholdValue;
                }
                else{ //flip the values since the library used for contouring will bound areas between the chosen value and the max value.
                    contourLevel = (float)lowerThresholdValue *-1 +  (float)(mapper.getDataMaxValue() - mapper.getDataMinValue() );
                    invertContours = true;
                }


                //Organize measurements into arrays.
                double[] measurementArray = new double[measurementPathObjects.size()];
                double[] invertedMeasurementArray = new double[measurementPathObjects.size()];
                int measurementIndex = 0;
                int scaleFactor = 25; //Down-sampling. This causes some rounding. Would be ideal for it to be = 1 but it makes computation much faster


                //Get centroid of objects with these measurements and invert if necessary
                Set<Point3> measurementSet = new HashSet<>();

                for (PathObject pathObject  : measurementPathObjects) {

                    if( !Double.isNaN( pathObject.getROI().getCentroidX() ) & !Double.isNaN(pathObject.getROI().getCentroidY()) ) {

                        double xLoc = pathObject.getROI().getCentroidX() / scaleFactor;
                        double yLoc = pathObject.getROI().getCentroidY() / scaleFactor;

                        double measurementVal;

                        double measurement = ( pathObject.getMeasurementList().getMeasurementValue(selectedMeasurement) );
                        double invertedMeasurement = -1 *( pathObject.getMeasurementList().getMeasurementValue(selectedMeasurement) )+ (mapper.getDataMaxValue() - mapper.getDataMinValue());

                        if(!invertContours){measurementVal = measurement;}
                        else{measurementVal =  invertedMeasurement;}

                        Point3 tempMeasurementPoint = new Point3(xLoc,yLoc,measurementVal);
                        measurementSet.add(tempMeasurementPoint);

                        measurementArray[measurementIndex] = measurement;
                        invertedMeasurementArray[measurementIndex] = invertedMeasurement;
                        measurementIndex++;
                    }

                }

                float[] measurementVals = new float[measurementSet.size()];
                float[] xLocations = new float[measurementSet.size()];
                float[] yLocations = new float[measurementSet.size()];

                int scaleIndex = 0;
                for( Point3 measurementPoint : measurementSet)
                {
                    xLocations[scaleIndex] = (float)measurementPoint.getX();
                    yLocations[scaleIndex] = (float)measurementPoint.getY();
                    measurementVals[scaleIndex] = (float)measurementPoint.getZ();

                    scaleIndex++;
                }

                //Perform gridding with gridding interpolators from the edu.mines library
                SibsonGridder2 gridder = new SibsonGridder2(measurementVals,xLocations, yLocations);
                Sampling sampleX = new Sampling(server.getWidth()/scaleFactor);
                Sampling sampleY = new Sampling(server.getHeight()/scaleFactor);
                gridder.useConvexHullBounds();
                gridder.setNullValue(0.0f);
                float[][] griddedData = gridder.grid( sampleX,sampleY);

                //Extract areas from bounding annotation to clip gridding artifacts
                Area annotationArea =  PathROIToolsAwt.getArea(parentObject.getROI());


                //The gridders can cause areas outside the annotaion to be non-zero so we clip areas outside the bounding one.
                /*This is an expensive method which scales badly but I have seen the method used below (intersection) create strange artifacts (possibly from edges of
                 the inner and bounding annotation crossing many times) so we will keep it around just in case*/
                for(int i = 0; i < griddedData.length; i++) {
                    for (int j = 0; j < griddedData[0].length; j++) {
                        if(!annotationArea.contains(j*scaleFactor,i*scaleFactor)) griddedData[i][j] = 0;
                    }
                }

                //These objects are created to extract the contours
                PlotPanel panel1 = new PlotPanel();
                ContoursView contoursView = panel1.addContours(griddedData);

                ArrayList<Point2D[]> pointSet = contoursView.getContourMaps(contourLevel);


                //Convert sets of points into areas and combine to form one large annotation
                Path2D contourPath = new Path2D.Float(Path2D.WIND_NON_ZERO);
                ArrayList<Vertices> contourVertices = new ArrayList<>();

                //Loop through each area to create a polygon so that we can check it's area and proceed accordingly
                for (int i = 0; i < pointSet.size(); i++) {

                    List<Point2> point2List = new ArrayList<>();

                    for (int j = 0; j < pointSet.get(i).length; j++) {

                        Point2D tempPoint2D = pointSet.get(i)[j];
                        Point2 quPathPoint = new Point2(tempPoint2D.getX() * scaleFactor, tempPoint2D.getY() * scaleFactor);
                        point2List.add(quPathPoint);

                    }

                    PolygonROI contourPoly = new PolygonROI(point2List);
                    double contourAreaMicronsSquared = contourPoly.getArea() * server.getPixelHeightMicrons() * server.getPixelWidthMicrons();
                    //Now that we have the individual polygon's area, we can decide whether to include it or not.
                    //At this point there is no great way of telling whether the polygons are holes or positive space so we'll filter holes later.

                    if( contourAreaMicronsSquared  > minAreaFilter) {

                        for (int j = 0; j < pointSet.get(i).length; j++) {

                            if (j == 0)
                                contourPath.moveTo(pointSet.get(i)[j].getX() * scaleFactor, pointSet.get(i)[j].getY() * scaleFactor);
                            else
                                contourPath.lineTo(pointSet.get(i)[j].getX() * scaleFactor, pointSet.get(i)[j].getY() * scaleFactor);


                        }


                    }

                }

                Area contourArea = new Area(contourPath);
                AWTAreaROI contourAreaROI;

                Path2D holelessContourPath = new Path2D.Float();
                //Now let's fill the holes if necessary
                if(!keepHoles){
                    PolygonROI[][] contourPolygons = PathROIToolsAwt.splitAreaToPolygons(contourArea);
                    for(PolygonROI contourPolygon : contourPolygons[1]){      //Polygons[1] contains positive space polygons, Polygons[0] contains the holes, if we dont want the holes we just make a new path that only contains the positive space

                            List<Point2> polyPoints = contourPolygon.getPolygonPoints();
                            Point2 point = polyPoints.get(0);
                            holelessContourPath.moveTo(point.getX(), point.getY());
                            for (int i = 1; i < polyPoints.size(); i++) {
                                point = polyPoints.get(i);
                                holelessContourPath.lineTo(point.getX(), point.getY());
                            }

                        }

                        Area holelessContourArea = new Area(holelessContourPath);
                    contourAreaROI = new AWTAreaROI(holelessContourArea);

                } else {

                    contourAreaROI = new AWTAreaROI(contourArea);
                }


                PathObject contourAnnotation = new PathAnnotationObject(contourAreaROI);
                String contourMeasurementName = invertContours ? "Contour Level (Inverted)" : "Contour Level";
                double contourValue = invertContours? lowerThresholdValue:upperThresholdValue;
                contourAnnotation.getMeasurementList().addMeasurement(contourMeasurementName, contourValue);
                contourAnnotation.setColorRGB(ColorTools.makeRGB(255,255,51));
                imageData.getHierarchy().getSelectionModel().clearSelection();
                imageData.getHierarchy().addPathObjectBelowParent(parentObject,contourAnnotation,true,true);
                /* Adding the new area properly to the hierarchy takes a long time since QuPath isn't fast at reassigning objects in a hierarchy but it
                 still seems like the right thing to do to preserve the structure */

            }
        };

        tasks.add(runnable);
    }

}








