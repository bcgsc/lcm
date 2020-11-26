package qupath.LMDExport;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.frame.ThresholdAdjuster;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import javafx.stage.FileChooser;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.html.HTMLImageElement;
import qupath.imagej.images.servers.ImagePlusServer;
import qupath.imagej.images.servers.ImagePlusServerBuilder;
import qupath.lib.geom.ImmutableDimension;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.PathImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.*;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.PathPoints;
import qupath.lib.roi.interfaces.PathShape;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.rois.vertices.Vertices;
import sun.security.ssl.HandshakeInStream;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;

import static org.opencv.core.CvType.CV_8UC1;

/**
 * Created by cschlosser on 01/06/2018.
 * This plugin looks for fiducial points titled "leica" and uses them to write the selected annotations to a file which can be imported on the Leica LMD7000 machine
 * It also produces an image used to guide the user in finding the same fiducials when the slide is loaded into the LMD machine.
 */
public class ExportLMDMapPlugin extends AbstractInteractivePlugin<BufferedImage> {

    final private static Logger logger = LoggerFactory.getLogger(ExportLMDMapPlugin.class);

    private ParameterList params;

    File outputFile;


    public ExportLMDMapPlugin() {


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
            boolean automaticAnnotations = params.getBooleanParameterValue("automaticPoints");
            boolean tileAnnotations = params.getBooleanParameterValue("tileAnnotations");
            double tileSize = params.getDoubleParameterValue("tileSize");

            @Override
            public void run() {

                PathObjectHierarchy hierarchy = imageData.getHierarchy();
                ImageServer server =  imageData.getServer();


                //If manual point inputs, check existence and validity
                //Else if automatic registration, run the automatic point setter
                List<Point2> registrationPoints = new ArrayList<>();
                if(!automaticAnnotations) {

                    Collection<PathObject> pathAnnotationPoints = hierarchy.getObjects(null, PathAnnotationObject.class );
                    Predicate<PathObject> pointCheck = p -> !p.isPoint() || !p.getDisplayedName().equals("leica") ;
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

                } else {
                    registrationPoints = runAutomaticRegPoints();
                    if(registrationPoints.size() != 3)
                    {
                        logger.error("Automatic Registration Point Generation Failed");
                        return;
                    }
                    PathObject regPointObjects = new PathAnnotationObject( new PointsROI(registrationPoints) );
                    regPointObjects.setName("leica registration (auto)");
                    hierarchy.addPathObject(regPointObjects,false);

                }


                List<PathObject> exportPathObjects;

                switch(exportSelection) {
                    //Todo: Add a centroid option for ablation/LIFT style laser capture?
                    case "Outer Annotations":

                        //In the case where we just want the outer annotations, the objects to export are the ones selected(filtered above)
                        //Tile if necessary;
                        //The leica LMD system imports the objects differently depending on size. Namely objects that are bigger than the FOV are imported as "Point to Point"
                        //objects which are slow and unreliable. The option is given to tile the areas so that they can all be smaller than the desired objective's FOV.
                        if(tileAnnotations)
                        {

                            //We only want to tile the ones larger than the tile size, which forces us to manually tile them instead of using the plugin
                            List<PathObject> smallObjects =  new ArrayList<>();
                            ImmutableDimension tileDims = new ImmutableDimension( (int)(tileSize/server.getPixelWidthMicrons()) ,(int)(tileSize/server.getPixelWidthMicrons()) );


                            List<Vertices> verticesList = AWTAreaROI.getVertices(PathROIToolsAwt.getShape(parentObject.getROI()) );
                            for( Vertices singleShapeVertices : verticesList) {

                                ROI pathROI =  new AWTAreaROI( PathROIToolsAwt.getShape( new PolygonROI(singleShapeVertices.getPoints()) ) );
                                PathObject tempPathObject = new PathAnnotationObject(pathROI);
                                if (pathROI.getBoundsHeight()*server.getPixelHeightMicrons() > tileSize || pathROI.getBoundsWidth() * server.getPixelWidthMicrons() > tileSize){
                                    //Only select the annotations that are larger than the tile size.
                                    Collection<? extends ROI> tiles = PathROIToolsAwt.computeTiledROIs (pathROI,tileDims,tileDims,true,0);
                                    for(ROI tile : tiles)
                                    {
                                        smallObjects.add(new PathAnnotationObject(tile));
                                    }
                                }else{smallObjects.add(tempPathObject);}

                            }


                            //hierarchy.addPathObjects(smallObjects, true);    //Uncomment if tiled objects need to be kept, although with many detected objects this a very long process.
                            exportPathObjects = smallObjects;

                            if (exportPathObjects.isEmpty() )
                            {
                                logger.error("No valid annotation tiles from which to export contours.");
                                return;
                            }

                        }
                        else{
                            exportPathObjects = new ArrayList<>();
                            exportPathObjects.addAll(imageData.getHierarchy().getSelectionModel().getSelectedObjects());
                        }

                        break;

                    case "Cell Boundaries":
                        //In case we want the cell boundaries, find objects within the selected annotations

                        exportPathObjects = new ArrayList<>();
                        for( PathObject selectedPathObject : imageData.getHierarchy().getSelectionModel().getSelectedObjects()){
                            exportPathObjects.addAll( selectedPathObject.getChildObjects() );
                        }

                        Predicate<PathObject> notCell = p -> !(p.getClass()== PathCellObject.class);
                        exportPathObjects.removeIf(notCell);
                        if(exportPathObjects.size() == 0)
                        {
                            logger.error("No Cells to Export");
                            return;
                        }
                        break;

                    // You can have any number of case statements.
                    default :
                        logger.error("Invalid Selection");
                        return;

                }



                if(outputFile == null){
                    logger.error("No Output File Selected");
                    return;
                }

                boolean succesfulWrite  = writeLeicaMap(exportPathObjects, registrationPoints, outputFile);
                if(succesfulWrite) {
                    File outputImageFile = new File(outputFile.getAbsolutePath().substring(0, outputFile.getAbsolutePath().lastIndexOf(".")) + ".png");
                    writeLeicaCalibrationImage(registrationPoints, outputImageFile);
                }
            }



            //-----------------Helpers-------------------//

            private boolean writeLeicaMap(Collection<PathObject> selectedPathObjects, List<Point2> calibPoints , File outFile ) {

                try {

                    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

                    // Image Data Root Element
                    Document doc = docBuilder.newDocument();
                    Element imageDataElement = doc.createElement("ImageData");
                    doc.appendChild(imageDataElement);

                    // Calibration elements
                    Element globalCoords = doc.createElement("GlobalCoordinates");
                    globalCoords.appendChild(doc.createTextNode("1"));
                    imageDataElement.appendChild(globalCoords);


                    for(int i  = 0; i<3;i++) {
                        Element xCoord1 = doc.createElement(String.format("X_CalibrationPoint_%d",i+1));
                        xCoord1.appendChild(doc.createTextNode(String.format("%.0f", calibPoints.get(i).getX())));
                        imageDataElement.appendChild(xCoord1);

                        Element yCoord1 = doc.createElement(String.format("Y_CalibrationPoint_%d",i+1));
                        yCoord1.appendChild(doc.createTextNode(String.format("%.0f", calibPoints.get(i).getY())));
                        imageDataElement.appendChild(yCoord1);

                    }

                    // Shape elements
                    int shapeSum = 0;
                    for(PathObject annotation: selectedPathObjects) {
                        shapeSum = shapeSum + AWTAreaROI.getVertices(PathROIToolsAwt.getShape(annotation.getROI()) ).size();
                    }
                    Element shapeCount = doc.createElement("ShapeCount");
                    shapeCount.appendChild(doc.createTextNode( String.format("%d", shapeSum) ) );
                    imageDataElement.appendChild(shapeCount);

                    int shapeIndex = 1;

                    for(PathObject annotation: selectedPathObjects){

                        //Get all the points from the annotation.
                        List<Vertices> annotationPoints = AWTAreaROI.getVertices(PathROIToolsAwt.getShape(annotation.getROI()) );

                        for( Vertices vertices : annotationPoints) {

                            Element shapeN = doc.createElement(String.format("Shape_%d",shapeIndex));
                            imageDataElement.appendChild(shapeN);

                            Element pointCount = doc.createElement("PointCount");
                            pointCount.appendChild(doc.createTextNode( String.format("%d",vertices.size()) ) );
                            shapeN.appendChild(pointCount);

                            int pointIndex = 1;
                            List<Point2> pointList = vertices.getPoints();

                            for (Point2 point : pointList) {

                                Element pointX = doc.createElement(String.format("X_%d", pointIndex));
                                pointX.appendChild(doc.createTextNode(String.format("%.0f", point.getX() )));
                                shapeN.appendChild(pointX);
                                Element pointY = doc.createElement(String.format("Y_%d", pointIndex));
                                pointY.appendChild(doc.createTextNode(String.format("%.0f", point.getY() )));
                                shapeN.appendChild(pointY);

                                pointIndex++;
                            }

                            shapeIndex++;
                        }

                    }

                    // write the content into xml file
                    TransformerFactory transformerFactory = TransformerFactory.newInstance();
                    Transformer transformer = transformerFactory.newTransformer();
                    DOMSource source = new DOMSource(doc);
                    StreamResult result = new StreamResult(outFile);

                    // Output to console for testing
                    // StreamResult result = new StreamResult(System.out);
                    transformer.transform(source, result);
                    logger.info("XML file saved");

                } catch (ParserConfigurationException pce) {
                    logger.error("Error writing XML File");
                    return false;
                } catch (TransformerException tfe) {
                    return false;
                }

                return true;

            }


            private void writeLeicaCalibrationImage(List<Point2> calibPoints , File outFile) {
                int fullImageDownsample = 8;
                int zoomedDownsample = 4;
                double downsampleRatio = fullImageDownsample / zoomedDownsample;


                ImageServer<BufferedImage> server = imageData.getServer();
                ImagePlusServer serverPlus = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(server);
                int subImageHeight = server.getHeight() / 3;
                int subImageWidth = server.getWidth() / 3;

                int pointROIwidth = (int) (subImageWidth / downsampleRatio);
                int pointROIheight = (int) (subImageHeight / downsampleRatio);
                int point1X = (int) (calibPoints.get(0).getX() - pointROIwidth / 2);
                int point1Y = (int) (calibPoints.get(0).getY() - pointROIheight / 2);
                int point2X = (int) (calibPoints.get(1).getX() - pointROIwidth / 2);
                int point2Y = (int) (calibPoints.get(1).getY() - pointROIheight / 2);
                int point3X = (int) (calibPoints.get(2).getX() - pointROIwidth / 2);
                int point3Y = (int) (calibPoints.get(2).getY() - pointROIheight / 2);

                RegionRequest regionFull = RegionRequest.createInstance(server.getPath(), fullImageDownsample, 0, 0, server.getWidth(), server.getHeight());
                RegionRequest regionPoint1 = RegionRequest.createInstance(server.getPath(), zoomedDownsample, point1X, point1Y, pointROIwidth, pointROIheight);
                RegionRequest regionPoint2 = RegionRequest.createInstance(server.getPath(), zoomedDownsample, point2X, point2Y, pointROIwidth, pointROIheight);
                RegionRequest regionPoint3 = RegionRequest.createInstance(server.getPath(), zoomedDownsample, point3X, point3Y, pointROIwidth, pointROIheight);

                BufferedImage pathImageFull = serverPlus.readImagePlusRegion(regionFull).getImage().getBufferedImage();
                BufferedImage pathImagePoint1 = serverPlus.readImagePlusRegion(regionPoint1).getImage().getBufferedImage();
                BufferedImage pathImagePoint2 = serverPlus.readImagePlusRegion(regionPoint2).getImage().getBufferedImage();
                BufferedImage pathImagePoint3 = serverPlus.readImagePlusRegion(regionPoint3).getImage().getBufferedImage();

                BufferedImage leicaCollage = new BufferedImage(pathImageFull.getWidth() + pathImagePoint1.getWidth(), pathImageFull.getHeight(), pathImageFull.getType());

                BufferedImage leicaCollageSubFull = leicaCollage.getSubimage(0, 0, pathImageFull.getWidth(), pathImageFull.getHeight());
                leicaCollageSubFull.setData(pathImageFull.getData());

                BufferedImage leicaCollageSubPoint1 = leicaCollage.getSubimage(pathImageFull.getWidth(), 0, pathImagePoint1.getWidth(), pathImagePoint1.getHeight());
                leicaCollageSubPoint1.setData(pathImagePoint1.getData());
                BufferedImage leicaCollageSubPoint2 = leicaCollage.getSubimage(pathImageFull.getWidth(), pathImagePoint1.getHeight() - 1, pathImagePoint1.getWidth(), pathImagePoint2.getHeight());
                leicaCollageSubPoint2.setData(pathImagePoint2.getData());
                BufferedImage leicaCollageSubPoint3 = leicaCollage.getSubimage(pathImageFull.getWidth(), pathImagePoint2.getHeight() + pathImagePoint1.getHeight() - 1, pathImagePoint3.getWidth(), pathImagePoint3.getHeight());
                leicaCollageSubPoint3.setData(pathImagePoint3.getData());


                //Add some boxes and text to show point order.
                Graphics2D mapGraphics = leicaCollage.createGraphics();
                mapGraphics.setColor(Color.green);
                mapGraphics.setStroke(new BasicStroke(1));
                int ovalWidth = 4;
                int ovalHeight = 4;
                mapGraphics.fillOval(pathImageFull.getWidth()+ pathImagePoint1.getWidth() / 2 -ovalWidth/2, pathImagePoint1.getHeight()/2 -ovalHeight/2,ovalWidth,ovalHeight);
                mapGraphics.drawOval(pathImageFull.getWidth()+ pathImagePoint2.getWidth() / 2 -ovalWidth/2, pathImagePoint1.getHeight()+pathImagePoint2.getHeight()/2-ovalHeight/2,ovalWidth,ovalHeight);
                mapGraphics.drawOval(pathImageFull.getWidth()+ pathImagePoint3.getWidth() / 2 -ovalWidth/2, pathImagePoint1.getHeight()+ pathImagePoint2.getHeight() +pathImagePoint3.getHeight()/2 -ovalHeight/2 ,ovalWidth,ovalHeight);
                mapGraphics.drawOval((int) calibPoints.get(0).getX() / (zoomedDownsample * (int) downsampleRatio)-ovalWidth/2, (int) calibPoints.get(0).getY() / (zoomedDownsample * (int) downsampleRatio)-ovalHeight/2,ovalWidth,ovalHeight);
                mapGraphics.drawOval((int) calibPoints.get(1).getX() / (zoomedDownsample * (int) downsampleRatio)-ovalWidth/2, (int) calibPoints.get(1).getY() / (zoomedDownsample * (int) downsampleRatio)-ovalHeight/2,ovalWidth,ovalHeight);
                mapGraphics.drawOval((int) calibPoints.get(2).getX() / (zoomedDownsample * (int) downsampleRatio)-ovalWidth/2, (int) calibPoints.get(2).getY() / (zoomedDownsample * (int) downsampleRatio)-ovalHeight/2,ovalWidth,ovalHeight);


                mapGraphics.setColor(Color.black);
                mapGraphics.setStroke(new BasicStroke(2));
                mapGraphics.drawRect(point1X / (zoomedDownsample * (int) downsampleRatio), point1Y / (zoomedDownsample * (int) downsampleRatio), pointROIwidth / (zoomedDownsample * (int) downsampleRatio), pointROIheight / (zoomedDownsample * (int) downsampleRatio));
                mapGraphics.drawRect(point2X / (zoomedDownsample * (int) downsampleRatio), point2Y / (zoomedDownsample * (int) downsampleRatio), pointROIwidth / (zoomedDownsample * (int) downsampleRatio), pointROIheight / (zoomedDownsample * (int) downsampleRatio));
                mapGraphics.drawRect(point3X / (zoomedDownsample * (int) downsampleRatio), point3Y / (zoomedDownsample * (int) downsampleRatio), pointROIwidth / (zoomedDownsample * (int) downsampleRatio), pointROIheight / (zoomedDownsample * (int) downsampleRatio));

                mapGraphics.drawRect(pathImageFull.getWidth(), 0, pathImagePoint1.getWidth(), pathImagePoint1.getHeight());
                mapGraphics.drawRect(pathImageFull.getWidth(), pathImagePoint1.getHeight() - 1, pathImagePoint1.getWidth(), pathImagePoint2.getHeight());
                mapGraphics.drawRect(pathImageFull.getWidth(), pathImagePoint2.getHeight() + pathImagePoint1.getHeight() - 1, pathImagePoint3.getWidth(), pathImagePoint3.getHeight());

                mapGraphics.setFont(new Font("Arial", Font.BOLD, 15));
                mapGraphics.setColor(Color.BLUE);
                mapGraphics.drawString("Point 1", (int) calibPoints.get(0).getX() / (zoomedDownsample * (int) downsampleRatio)+20, (int) calibPoints.get(0).getY() / (zoomedDownsample * (int) downsampleRatio) + 20);
                mapGraphics.drawString("Point 2", (int) calibPoints.get(1).getX() / (zoomedDownsample * (int) downsampleRatio)+20, (int) calibPoints.get(1).getY() / (zoomedDownsample * (int) downsampleRatio) + 20);
                mapGraphics.drawString("Point 3", (int) calibPoints.get(2).getX() / (zoomedDownsample * (int) downsampleRatio)+20, (int) calibPoints.get(2).getY() / (zoomedDownsample * (int) downsampleRatio) + 20);
                mapGraphics.drawString("Point 1", pathImageFull.getWidth()+20, 20);
                mapGraphics.drawString("Point 2", pathImageFull.getWidth()+20, pathImagePoint1.getHeight() - 1 + 20);
                mapGraphics.drawString("Point 3", pathImageFull.getWidth()+20, pathImagePoint2.getHeight() + pathImagePoint1.getHeight() - 1 + 20);


                try {

                    boolean imageSaved = ImageIO.write(leicaCollage, "png", outFile);
                    if(imageSaved) logger.info("Leica map Image saved");
                } catch (IOException e) {
                    logger.error("Error saving image of map");
                }

            }



            private List<Point2> runAutomaticRegPoints() {

                int downsample = 4;

                ImageServer<BufferedImage> server = imageData.getServer();
                ImagePlusServer serverPlus = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(server);


                RegionRequest regionFull = RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight());
                PathImage<ImagePlus> pathImage = serverPlus.readImagePlusRegion(regionFull);
                ImagePlus imp = pathImage.getImage();
                IJ.run(imp, "8-bit", "");
                ThresholdAdjuster.setMode("B&W");
                IJ.setRawThreshold(imp, 0, 215, null);
                IJ.run(imp, "Convert to Mask", "");
                IJ.run(imp, "Fill Holes", "");
                int minIslandSize = (int)(50000/(server.getAveragedPixelSizeMicrons() * server.getAveragedPixelSizeMicrons() * downsample * downsample));
                IJ.run(imp, "Analyze Particles...", String.format("size=%d-Infinity pixel show=Masks in_situ", minIslandSize) );

                ByteProcessor bp = imp.getProcessor().convertToByteProcessor();

                byte[] pixels = ((DataBufferByte) bp.getBufferedImage().getRaster().getDataBuffer()).getData();
                Mat cornerImage = new Mat(bp.getHeight(),bp.getWidth(), CV_8UC1);
                cornerImage.put(0,0, pixels);

                MatOfPoint cornerPoints = new MatOfPoint();

                bp.filter(ImageProcessor.MAX);
                byte[] maskPixels = ((DataBufferByte) bp.getBufferedImage().getRaster().getDataBuffer()).getData();
                Mat cornerMask = new Mat(bp.getHeight(),bp.getWidth(), CV_8UC1);
                cornerMask.put(0,0, maskPixels);


                Imgproc.goodFeaturesToTrack( cornerImage,cornerPoints,100,0.1,100,cornerMask,11,true,0.04);
                List<org.opencv.core.Point> cornerPointsExport = cornerPoints.toList();

                List<Point2> qupathPoints = new ArrayList<>();
                for(org.opencv.core.Point cvPoint : cornerPointsExport)
                {
                    qupathPoints.add( new Point2(cvPoint.x*downsample,cvPoint.y*downsample) );
                }

                List<Point2> qupathRegPoints = new ArrayList<>();
                if(qupathPoints.size() > 3) {
                    qupathPoints.sort(Comparator.comparing(Point2::getX));
                    List<Point2> lowestX = qupathPoints.subList(0, qupathPoints.size() / 5);
                    List<Point2> highestX = qupathPoints.subList( (int)(qupathPoints.size() * 0.8) , qupathPoints.size());
                    lowestX.sort(Comparator.comparing(Point2::getY));
                    highestX.sort(Comparator.comparing(Point2::getY));


                    Point2 regPoint1 = lowestX.get(0);
                    Point2 regPoint2 = highestX.get(0);
                    Point2 regPoint3 = lowestX.get(lowestX.size() - 1);
                    qupathRegPoints.add(regPoint1);
                    qupathRegPoints.add(regPoint2);
                    qupathRegPoints.add(regPoint3);
                }
                return qupathRegPoints;
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
        this.params.addBooleanParameter("automaticPoints", "Automatically Select Registration Points?", false);
        this.params.addBooleanParameter("tileAnnotations", "Tile Annotations", false);
        this.params.addDoubleParameter( "tileSize", "Tile Size",500.0 ,"um", 100.0 ,1000.0);

        return params;
    }


    @Override
    public String getName() {
        return "Export LMD Map";
    }

    @Override
    public String getLastResultsDescription() {
        return null;
    }


    @Override
    public String getDescription() {
        return "Export Cell or Annotation Boundaries for Laser Capture on Leica LMD7000";
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

        outputFile = QuPathGUI.getSharedDialogHelper().promptToSaveFile( "XML file",null,pluginRunner.getImageData().getServer().getDisplayedImageName()+"_leica-map","xml files","xml");

        PathObjectHierarchy hierarchy = pluginRunner.getHierarchy();
        ArrayList<PathObject> selectedObjects = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());

        PathObjectTools.filterROIs(selectedObjects, PathShape.class);

        hierarchy.getSelectionModel().clearSelection();
        hierarchy.getSelectionModel().selectObjects(selectedObjects);


    }
}