package qupath.LMDExport;

import edu.mines.jtk.dsp.Sampling;
import edu.mines.jtk.interp.SibsonGridder2;
import edu.mines.jtk.mosaic.ContoursView;
import edu.mines.jtk.mosaic.PlotPanel;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import javafx.scene.shape.*;
import qupath.imagej.images.servers.ImagePlusServer;
import qupath.imagej.images.servers.ImagePlusServerBuilder;
import qupath.imagej.objects.ROIConverterIJ;
import qupath.imagej.processing.SimpleThresholding;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.geom.Point3;
import qupath.lib.gui.ImageWriterTools;
import qupath.lib.gui.helpers.MeasurementMapper;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.overlays.HierarchyOverlay;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.PathImage;
import qupath.lib.images.servers.ImageIoImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.*;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.plugins.AbstractPlugin;
import qupath.lib.plugins.PathPlugin;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.Parameter;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.plugins.workflow.SimplePluginWorkflowStep;
import qupath.lib.plugins.workflow.WorkflowStep;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.rois.vertices.Vertices;
import sun.nio.cs.ext.MS50220;

import javax.imageio.ImageWriter;
import javax.swing.plaf.synth.Region;
import java.awt.*;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
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
 * The contour is found by thresholding the colourmap and then converting the roi from imageJ.
 * This method differs from the gridded interpolation version in that it doesn't assign a class probability to space
 * which does't contain nuclei.
 */
public class DrawThresholdContourPlugin extends AbstractPlugin<BufferedImage>{

    private String selectedMeasurement;
    private double minAreaFilter;
    private double lowerThresholdValue;
    private double upperThresholdValue;
    private DrawContourMapPanel.ThresholdLevel thresholdLevel;
    private String lastMessage = null;
    private boolean keepHoles;

    public DrawThresholdContourPlugin() {
        this.selectedMeasurement = null;
        this.minAreaFilter = 0.0;
        this.lowerThresholdValue = 0.0;
        this.upperThresholdValue = 0.0;
        this.thresholdLevel = DrawContourMapPanel.ThresholdLevel.HIGH;
        this.keepHoles = true;
    }

    public DrawThresholdContourPlugin(final String selectedMeasurement, final double minAreaFilter, final double lowerThresholdValue, final double upperThresholdValue, final DrawContourMapPanel.ThresholdLevel thresholdLevel, final boolean keepHoles) {
        this.selectedMeasurement = selectedMeasurement;
        this.minAreaFilter = minAreaFilter;
        this.lowerThresholdValue = lowerThresholdValue;
        this.upperThresholdValue = upperThresholdValue;
        this.thresholdLevel = thresholdLevel;
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
        return "Draw Contours around objects of selected measurement";
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
                ImageServer<BufferedImage> buffServer= imageData.getServer();
                int downsample = 4;
                ArrayList<PathObject> measurementPathObjects = new ArrayList<>();
                imageData.getHierarchy().getDescendantObjects(parentObject,measurementPathObjects, PathDetectionObject.class);



                //parent ROI
                ROI parentROI = parentObject.getROI();
                RegionRequest regionRequest = RegionRequest.createInstance(server.getPath(), downsample,parentROI);
                ImagePlusServer plusServer = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(server);
                PathImage<ImagePlus> pathImage = plusServer.readImagePlusRegion(regionRequest);

                BufferedImage img = buffServer.readBufferedImage(regionRequest);
                ThresholdMeasurementMapper mapper = new ThresholdMeasurementMapper(selectedMeasurement, measurementPathObjects);
                mapper.setExcludeOutsideRange(true);
                mapper.setDisplayMaxValue(upperThresholdValue);
                mapper.setDisplayMinValue(lowerThresholdValue);
                OverlayOptions overlayOptions = new OverlayOptions();
                overlayOptions.setShowAnnotations(false);
                imageData.getHierarchy().getSelectionModel().clearSelection();
                overlayOptions.setMeasurementMapper(mapper);
                overlayOptions.setCellDisplayMode(OverlayOptions.CellDisplayMode.BOUNDARIES_ONLY);
                overlayOptions.setFillObjects(true);
                HierarchyOverlay hierarchyOverlay = new HierarchyOverlay(null,overlayOptions,imageData);


                List<? extends PathOverlay> overlayLayers = Collections.singletonList(hierarchyOverlay);
                //Paint the area white to clear the background.
                Graphics2D g2d = img.createGraphics();
                g2d.setColor(Color.WHITE);
                g2d.fill(new Rectangle(0,0,img.getWidth(),img.getHeight() ) );
                //Create a transform to map to the object space
                AffineTransform transform = AffineTransform.getScaleInstance(1./regionRequest.getDownsample(), 1./regionRequest.getDownsample());
                transform.translate(-regionRequest.getX(), -regionRequest.getY());
                g2d.setTransform(transform);

                //Add the overlay of cells
                for (PathOverlay overlay : overlayLayers) {
                    overlay.paintOverlay(g2d, regionRequest, regionRequest.getDownsample(), null, true);
                }
                g2d.dispose();


                //We use a custom MeasurementMapper (ThresholdMeasurementMapper above) calling a custom colorMapper which sets the desired measurements ranges to be zero in the red channel for any measurement above the upper threshold
                // and zero in blue channel for any measurement below the lower threshold, but white everywhere else.
                // We use zero because the overlay shows a slightly different colour for the cell boundaries but will show black for both the cell interior and boundary if it's black
                // which gives a more homogeneous image to threshold.
                // Depending on whether the user want the upper end or lower end, the corresponding channel (red or blue) is thresholded for the zero values (neg infinity to 1)
                ColorProcessor colorProcessor = new ColorProcessor(img);
                ByteProcessor bpRed = new ByteProcessor(colorProcessor.getWidth(),colorProcessor.getHeight());
                ByteProcessor bpBlue = new ByteProcessor(colorProcessor.getWidth(),colorProcessor.getHeight());
                ByteProcessor bpGreen = new ByteProcessor(colorProcessor.getWidth(),colorProcessor.getHeight());
                ByteProcessor bpThresh = new ByteProcessor(colorProcessor.getWidth(),colorProcessor.getHeight());

                colorProcessor.getChannel(1, bpRed);
                colorProcessor.getChannel(2, bpGreen);
                colorProcessor.getChannel(3, bpBlue);

                switch(thresholdLevel){
                    case HIGH:
                        bpThresh = bpRed;
                        break;
                    case MID:
                        bpThresh = bpGreen;
                        break;
                    case LOW:
                        bpThresh = bpBlue;
                        break;
                }

                bpThresh.setThreshold(Double.NEGATIVE_INFINITY, 0, ImageProcessor.NO_LUT_UPDATE);
                //bpThresh.dilate();
                //bpThresh.erode();



                ThresholdToSelection thresholdToSelection = new ThresholdToSelection();
                Roi thresholdRoi = thresholdToSelection.convert(bpThresh);

                colorProcessor.setRoi(thresholdRoi);
                ROI contourROI = ROIConverterIJ.convertToPathROI(thresholdRoi,pathImage);





                //Now we'll filter the areas and get rid of the holes if necessary
                Path2D finalPath = new Path2D.Float(Path2D.WIND_NON_ZERO);
                PolygonROI[][] contourPolygons = PathROIToolsAwt.splitAreaToPolygons(PathROIToolsAwt.getArea(contourROI));

                for(PolygonROI contourPolygon : contourPolygons[0] ){
                    double polygonArea = contourPolygon.getArea() * imageData.getServer().getPixelHeightMicrons() * imageData.getServer().getPixelWidthMicrons() ;
                    if(keepHoles && polygonArea > minAreaFilter){

                        //Double smooth the points. We should likely set the degree of smoothing and downsampling as parameters but for simplicity we'll keep them fixed for now
                        List<Point2> polyPoints = contourPolygon.getPolygonPoints();
                        List<Point2> intermediatePolyPoints = ROIHelpers.smoothPoints(polyPoints);
                        List<Point2> smoothedPolyPoints = ROIHelpers.smoothPoints(intermediatePolyPoints);
                        Point2 point = smoothedPolyPoints.get(0);
                        finalPath.moveTo(point.getX(), point.getY());
                        for (int i = 1; i < smoothedPolyPoints.size(); i++) {
                            point = smoothedPolyPoints.get(i);
                            finalPath.lineTo(point.getX(), point.getY());
                        }

                    }
                }

                for(PolygonROI contourPolygon : contourPolygons[1] ){
                    double polygonArea = contourPolygon.getArea() * imageData.getServer().getPixelHeightMicrons() * imageData.getServer().getPixelWidthMicrons() ;
                    if( polygonArea > minAreaFilter){

                        //Double smooth the points. We should likely set the degree of smoothing and downsampling as parameters but for simplicity we'll keep them fixed for now
                        List<Point2> polyPoints = contourPolygon.getPolygonPoints();
                        List<Point2> intermediatePolyPoints = ROIHelpers.smoothPoints(polyPoints);
                        List<Point2> smoothedPolyPoints = ROIHelpers.smoothPoints(intermediatePolyPoints);
                        Point2 point = smoothedPolyPoints.get(0);
                        finalPath.moveTo(point.getX(), point.getY());
                        for (int i = 1; i < smoothedPolyPoints.size(); i++) {
                            point = smoothedPolyPoints.get(i);
                            finalPath.lineTo(point.getX(), point.getY());
                        }

                    }
                }


                Area finalContourArea =  new Area(finalPath);
                PathObject contourAnnotation = new PathAnnotationObject(new AWTAreaROI(finalContourArea));



                String contourHigh = "High Contour Level";
                String contourLow = "Low Contour Level";
                String thresholdLevelName = "none";
                double upperValue = Double.NaN;
                double lowerValue = Double.NaN;

                switch(thresholdLevel){
                    case HIGH:
                        thresholdLevelName = "Contour High";
                        upperValue = upperThresholdValue;
                        break;
                    case MID:
                        thresholdLevelName = "Contour Mid";
                        upperValue = upperThresholdValue;
                        lowerValue = lowerThresholdValue;
                        break;
                    case LOW:
                        thresholdLevelName = "Contour Low";
                        lowerValue = lowerThresholdValue;
                        break;
                }


                contourAnnotation.setName(parentObject.getROI().toString() + " - " + thresholdLevelName );
                contourAnnotation.getMeasurementList().addMeasurement(selectedMeasurement+", "+ contourHigh , upperValue);
                contourAnnotation.getMeasurementList().addMeasurement(selectedMeasurement+", "+ contourHigh, lowerValue);

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








