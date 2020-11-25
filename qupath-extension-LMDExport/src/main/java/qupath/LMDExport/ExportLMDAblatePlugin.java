package qupath.LMDExport;

import com.opencsv.*;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.jfree.graphics2d.svg.SVGGraphics2D;
import org.jfree.graphics2d.svg.SVGUnits;
import org.jfree.graphics2d.svg.SVGUtils;
import org.jfree.graphics2d.svg.ViewBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.*;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.PathShape;
import qupath.lib.rois.vertices.MutableVertices;


import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;



/**
 * Created by cschlosser on 01/06/2018.
 * This plugin looks for fiducial points titled "leica" and uses them to write the selected annotations to a file which can be imported on the Leica LMD7000 machine
 * It also produces an image used to guide the user in finding the same fiducials when the slide is loaded into the LMD machine.
 */
public class ExportLMDAblatePlugin extends AbstractInteractivePlugin<BufferedImage> {

    final private static Logger logger = LoggerFactory.getLogger(ExportLMDMapPlugin.class);
    private ParameterList params;
    File outputFile;
    File registrationFile;


    public ExportLMDAblatePlugin() {
    }


    public boolean requestLiveUpdate() {
        return false;
    }


    @Override
    public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
        List<Class<? extends PathObject>> list = new ArrayList<>(1);
        list.add(PathAnnotationObject.class);
        return list;
    }


    @Override
    protected void addRunnableTasks(final ImageData<BufferedImage> imageData, final PathObject parentObject, List<Runnable> tasks) {

        Runnable runnable =  new Runnable() {

            //Parse inputs
            String exportSelection = (String)params.getChoiceParameterValue("exportObjects");
            int filterArea = (int)params.getIntParameterValue("filterArea");

            @Override
            public void run() {

                PathObjectHierarchy hierarchy = imageData.getHierarchy();
                ImageServer server = imageData.getServer();

                //If manual point inputs, check existence and validity
                List<Point2> registrationPoints = new ArrayList<>();

                Collection<PathObject> pathAnnotationPoints = hierarchy.getObjects(null, PathAnnotationObject.class);
                Predicate<PathObject> pointCheck = p -> !p.isPoint() || !p.getDisplayedName().equals("registration");
                pathAnnotationPoints.removeIf(pointCheck);

                if (pathAnnotationPoints.isEmpty()) {
                    logger.error("No points for Leica registration");
                    return;
                }

                for (PathObject pathPointSet : pathAnnotationPoints) {
                    if (pathPointSet.getROI().getPolygonPoints().size() != 3) {
                        logger.error("Incorrect number of registration points; require 3");
                        return;
                    }
                    registrationPoints.addAll(pathPointSet.getROI().getPolygonPoints());
                }


                List<PathObject> exportPathObjects;

                switch (exportSelection) {
                    //Todo: Add a centroid option for ablation/LIFT style laser capture?
                    case "Outer Annotations":

                        exportPathObjects = new ArrayList<>();
                        exportPathObjects.addAll(imageData.getHierarchy().getSelectionModel().getSelectedObjects());

                        break;

                    case "Cell Boundaries":
                        //In case we want to just blast cell centres

                        exportPathObjects = new ArrayList<>();
                        for (PathObject selectedPathObject : imageData.getHierarchy().getSelectionModel().getSelectedObjects()) {
                            exportPathObjects.addAll(selectedPathObject.getChildObjects());
                        }

                        Predicate<PathObject> notCell = p -> !(p.getClass() == PathCellObject.class);
                        exportPathObjects.removeIf(notCell);
                        if (exportPathObjects.size() == 0) {
                            logger.error("No Cells to Export");
                            return;
                        }
                        break;

                    // You can have any number of case statements.
                    default:
                        logger.error("Invalid Selection");
                        return;

                }


                if (outputFile == null) {
                    logger.error("No Output File Selected");
                    return;
                }

                boolean succesfulWrite = writeAblationMap(exportPathObjects, registrationPoints, outputFile);
                if (!succesfulWrite) {
                    logger.info("Unsuccessful ablation map write");
                } else {
                    logger.info("Successful ablation map write");
                }


            }


            //-----------------Helpers-------------------//

            private boolean writeAblationMap(Collection<PathObject> selectedPathObjects, List<Point2> calibPoints , File outFile ) {

                //Load registration points from calibration file
                //Calculate transformation between calibration points and "registration" points marked by user.
                //Transform all of the annotation shapes from the slide
                //Save the transformed shapes as an svg

                try {
                    CSVReader reader = new CSVReader(new FileReader(registrationFile));
                    List<String[]> pointList = reader.readAll();

                    double[] xLaserPoints = new double[3];
                    double[] yLaserPoints = new double[3];

                    for(int i = 0 ; i < pointList.size(); i++)
                    {
                        xLaserPoints[i] = Float.valueOf(pointList.get(i)[0]);
                        yLaserPoints[i] = Float.valueOf(pointList.get(i)[1])*-1;
                    }

                    if(xLaserPoints.length !=3 || yLaserPoints.length != 3){
                            logger.error("Incorrect number of registration points in registration file");
                            return false;
                        }

                    double[] xImagePoints = new double[3];
                    double[] yImagePoints = new double[3];

                    for(int i = 0; i< calibPoints.size(); i++){
                        xImagePoints[i] = calibPoints.get(i).getX();
                        yImagePoints[i] = calibPoints.get(i).getY();
                    }


                    AffineTransform image2LaserTransformation = deriveAffineTransform(xImagePoints,yImagePoints,xLaserPoints,yLaserPoints);

                    SVGGraphics2D ablationMap = new SVGGraphics2D( 0, 0, SVGUnits.MM);
                    ViewBox viewBox = new ViewBox(-59, -59, 59, 59);
                    ablationMap.setStroke(new BasicStroke(0.0f));
                    ablationMap.setPaint(new Color(125,125,125));
                    for (PathObject exportObject : selectedPathObjects) {
                        //Very small polygons cause issues when importing to the laser software WeldMark so we have to filter the very small ones
                        //TODO: More robust filtering of very small regions

                        if(exportObject.getROI().getROIType()=="Area") {

                            PolygonROI[][] splitPolygons = PathROIToolsAwt.splitAreaToPolygons(PathROIToolsAwt.getArea(exportObject.getROI()));
                            Path2D areaPath = new Path2D.Float(Path2D.WIND_NON_ZERO);

                            //We will check the size of each polygon and only add it back if the area is big enough.

                            for (int i = 0; i < splitPolygons.length; i++) {
                                for (int j = 0; j < splitPolygons[i].length; j++) {

                                    double polygonArea =  splitPolygons[i][j].getArea() *  imageData.getServer().getPixelWidthMicrons() * imageData.getServer().getPixelHeightMicrons() ; //Average of both directions

                                    if ( polygonArea > filterArea ) {
                                        areaPath.moveTo(splitPolygons[i][j].getPolygonPoints().get(0).getX(), splitPolygons[i][j].getPolygonPoints().get(0).getY());
                                        for (int k = 1; k < splitPolygons[i][j].getPolygonPoints().size(); k++) {

                                            areaPath.lineTo(splitPolygons[i][j].getPolygonPoints().get(k).getX(), splitPolygons[i][j].getPolygonPoints().get(k).getY());

                                        }

                                    }

                                }
                            }

                            Area filteredAblationArea = new Area(areaPath);
                            ablationMap.fill(image2LaserTransformation.createTransformedShape(filteredAblationArea));

                        } else ablationMap.fill(image2LaserTransformation.createTransformedShape(PathROIToolsAwt.getShape(exportObject.getROI())));

                    }

                    SVGUtils.writeToSVG(outFile, ablationMap.getSVGElement() );//null, true, viewBox, true, null) );//ablationMap.getSVGDocument());

                } catch (java.io.IOException e){
                    logger.error("Calibration file could not be loaded");
                }
                return true;
            }




            //Nicely organized from : https://stackoverflow.com/questions/21270892/generate-affinetransform-from-3-points

            private  AffineTransform deriveAffineTransform(
                    double[] oldX, double[] oldY,
                    double[] newX, double[] newY ) {

                double[][] oldData = { {oldX[0], oldX[1], oldX[2]}, {oldY[0], oldY[1], oldY[2]}, {1, 1, 1} };
                RealMatrix oldMatrix = MatrixUtils.createRealMatrix(oldData);

                double[][] newData = { {newX[0], newX[1], newX[2]}, {newY[0], newY[1], newY[2]} };
                RealMatrix newMatrix = MatrixUtils.createRealMatrix(newData);

                RealMatrix inverseOld = new LUDecomposition(oldMatrix).getSolver().getInverse();
                RealMatrix transformationMatrix = newMatrix.multiply(inverseOld);

                double m00 = transformationMatrix.getEntry(0, 0);
                double m01 = transformationMatrix.getEntry(0, 1);
                double m02 = transformationMatrix.getEntry(0, 2);
                double m10 = transformationMatrix.getEntry(1, 0);
                double m11 = transformationMatrix.getEntry(1, 1);
                double m12 = transformationMatrix.getEntry(1, 2);

                return new AffineTransform(m00, m10, m01, m11, m02, m12);
            }




        };

        tasks.add(runnable);
    }


    @Override
    public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {

        List<String> exportOptions = new ArrayList<>();
        exportOptions.add("Outer Annotations");
        exportOptions.add("Cell Boundaries");

        params = new ParameterList();
        this.params.addChoiceParameter("exportObjects","Objects to Export",exportOptions.get(0),exportOptions);

        this.params.addIntParameter("filterArea","Minimum Area of Features to export ",50,  GeneralTools.micrometerSymbol()+ "^2",0,1000);

        return params;
    }


    @Override
    public String getName() {
        return "Export LMD Ablation Map";
    }

    @Override
    public String getLastResultsDescription() {
        return null;
    }


    @Override
    public String getDescription() {
        return "Export Cell or Annotation Boundaries for Laser Capture on Laser Ablation Hardware";
    }


    @Override
    protected Collection<? extends PathObject> getParentObjects(final PluginRunner<BufferedImage> runner) {
        return Collections.singletonList(runner.getImageData().getHierarchy().getRootObject());
    }


    @Override
    protected void addWorkflowStep(final ImageData<BufferedImage> imageData, final String arg) {
    }

    @Override
    protected void preprocess(final PluginRunner<BufferedImage> pluginRunner){

        registrationFile = QuPathGUI.getSharedDialogHelper().promptForFile ( "Load Laser Registration Points",null,"Fixed calibration point set in laser software","csv files","csv");
        outputFile = QuPathGUI.getSharedDialogHelper().promptToSaveFile( "Save Ablation Map ",null,pluginRunner.getImageData().getServer().getDisplayedImageName()+"_leica-map","svg files","svg");

        PathObjectHierarchy hierarchy = pluginRunner.getHierarchy();
        ArrayList<PathObject> selectedObjects = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());

        //Filter out any non-shape objects selected
        PathObjectTools.filterROIs(selectedObjects, PathShape.class);

        hierarchy.getSelectionModel().clearSelection();
        hierarchy.getSelectionModel().selectObjects(selectedObjects);


    }
}