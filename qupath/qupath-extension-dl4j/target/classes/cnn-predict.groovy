
/**
 * Created by cschlosser on 29/05/2017.
 */




//QuPathGUI.ExtensionClassLoader extensionClassLoader = (QuPathGUI.ExtensionClassLoader)QuPathGUI.getClassLoader();
//extensionClassLoader.refresh();


import org.datavec.image.loader.NativeImageLoader
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.util.ModelSerializer
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler
import org.nd4j.linalg.api.ndarray.INDArray
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import qupath.imagej.images.servers.ImagePlusServer
import qupath.imagej.images.servers.ImagePlusServerBuilder
import qupath.lib.images.ImageData
import qupath.lib.scripting.QPEx
import qupath.lib.images.servers.ImageServer
import qupath.lib.regions.RegionRequest
import qupath.lib.objects.PathObject


import java.awt.image.BufferedImage
import java.util.function.Predicate


Logger logger = LoggerFactory.getLogger("CNN-Predict");


ImageData currentImageData = QPEx.getCurrentImageData();
if(currentImageData == null)
{
    logger.error("Load slide and set up annotations before running script!")
    return
}

ImageServer<BufferedImage> serverOriginal = currentImageData.getServer()
ImagePlusServer server = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(serverOriginal)
path = server.getPath();
downsample = 1;
//Export image name formating
ext = ".jpg"


//Find CNN Model
File directory = new File("");
def modelFile = QPEx.getQuPath().getDialogHelper().promptForFile("Select CNN Model",directory,null,null)

Predicate<PathObject> upperAnnotation = { s -> s.getLevel() == 1};
QPEx.selectObjects(upperAnnotation)
QPEx.runPlugin('qupath.lib.algorithms.TilerPlugin', '{"tileSizeMicrons": 50.0 ,  "trimToROI": true,  "makeAnnotations": false,  "removeParentAnnotation": false}');
QPEx.deselectAll()


DataNormalization scaler = new ImagePreProcessingScaler(0, 1);
NativeImageLoader loader = new NativeImageLoader(100, 100, 3);

//Restore CNN
MultiLayerNetwork restoredCNN = ModelSerializer.restoreMultiLayerNetwork(modelFile);

for( Object in QPEx.getObjects(upperAnnotation) )
{
    for( Child in Object.getChildObjects() )
    {
        if( Child.getROI().getBoundsWidth() == 100 && Child.getROI().getBoundsHeight() == 100 )
        {

            RegionRequest request = RegionRequest.createInstance(path, downsample, Child.getROI())

            //Get image object
            BufferedImage img = server.readRegion(request).getImage();
            //Image img = server.readImagePlusRegion(request).getImage(false)
            INDArray imageArray = loader.asMatrix(img);
            //Nomalize Image
            scaler.transform(imageArray);

            //Find Probablity of Tumour
            INDArray[] output = restoredCNN.output(imageArray,false);
            //print(output[0].toString())

            double tumorProb = output[0][1];

            // Add new measurement to the measurement list of the detection
            Child.getMeasurementList().addMeasurement("Tumour Probablity", tumorProb)
            // It's important for efficiency reasons to close the list
            Child.getMeasurementList().closeList()

        }

    }


}

QPEx.selectObjects(upperAnnotation)
QPEx.runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin', '{"fwhmMicrons": 50.0 ,  "smoothWithinClasses": false,  "useLegacyNames": false}')
QPEx.selectObjects(upperAnnotation)
QPEx.runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin', '{"fwhmMicrons": 100.0 ,  "smoothWithinClasses": false,  "useLegacyNames": false}')
QPEx.selectObjects(upperAnnotation)
QPEx.runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin', '{"fwhmMicrons": 150.0 ,  "smoothWithinClasses": false,  "useLegacyNames": false}')
QPEx.selectObjects(upperAnnotation)
QPEx.runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin', '{"fwhmMicrons": 200.0 ,  "smoothWithinClasses": false,  "useLegacyNames": false}')

QPEx.deselectAll()
logger.println("Class Predictions Complete")
