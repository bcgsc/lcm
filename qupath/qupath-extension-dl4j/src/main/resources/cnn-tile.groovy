
/**
 * Created by cschlosser on 17/05/2017.
 */


import org.slf4j.Logger
import org.slf4j.LoggerFactory
import qupath.lib.images.ImageData
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.scripting.QPEx
import qupath.imagej.images.servers.ImagePlusServer
import qupath.imagej.images.servers.ImagePlusServerBuilder
import qupath.lib.images.servers.ImageServer
import qupath.lib.regions.RegionRequest
import qupath.lib.gui.ImageWriterTools
import qupath.lib.objects.PathObject
import qupath.lib.roi.PathROIToolsAwt
import qupath.lib.roi.PathObjectToolsAwt

import java.awt.image.BufferedImage
import java.util.function.Predicate


/* Our aim here is to decompose relevant image areas (tissue detection) into tiles and divide them based on whether they are
in annotations based on
 */

//Input State: tissue detection performed and Tumour annotations made


Logger logger = LoggerFactory.getLogger("CNN-Tile");


//Set up some image server variables
ImageData currentImageData = QPEx.getCurrentImageData();
if(currentImageData == null)
{
    logger.error("Load slide and set up annotations before running script!")
    return
}

ImageServer<BufferedImage> serverOriginal = currentImageData.getServer()
ImagePlusServer server = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(serverOriginal)

//Export image name formating
ext = ".jpg"

//Downsample factor for images - set to 1 unless otherwise specified
downsample = 1

String serverName = serverOriginal.getShortServerName()
path = server.getPath();

//Select Output Directory

def directory = QPEx.getQuPath().getDialogHelper().promptForDirectory(null)
String dirOutput = directory


def subdir = new File(dirOutput,serverName);
                subdir.mkdir()

def subdirTumor = new File(dirOutput + "/" + serverName,"tumor")
                subdirTumor.mkdir()
def subdirNormal = new File(dirOutput + "/" + serverName,"normal")
                subdirNormal.mkdir()

 

//Select and tile tumour area
Predicate<PathObject> tumor = { s -> s.getPathClass().toString() == "Tumor"};
QPEx.selectObjects(tumor)
QPEx.runPlugin('qupath.lib.algorithms.TilerPlugin', '{"tileSizeMicrons": 50.0 ,  "trimToROI": true,  "makeAnnotations": false,  "removeParentAnnotation": false}');
QPEx.deselectAll()

//Export Tumour images
imageData = QPEx.getCurrentImageData()
overlayOptions = QPEx.getCurrentViewer().getOverlayOptions()
overlayOptions.setShowObjects(false) 
overlayOptions.setShowAnnotations(false)


for( Object in QPEx.getObjects(tumor) )
{
   for( Child in Object.getChildObjects() )
      {
          if( Child.getROI().getBoundsWidth() == 100 && Child.getROI().getBoundsHeight() == 100 )
           {
                
                frame = Child.getDisplayedName();
                RegionRequest request = RegionRequest.createInstance(path, downsample, Child.getROI())
                String name = String.format("tumor_%s%s",frame,ext)
                File file1 = new File(subdirTumor, name)
                ImageWriterTools.writeImageRegionWithOverlay(imageData, overlayOptions, request, file1.getAbsolutePath())
                      
           }  
             
      }
}


//Subtract tumor from larger areas

Predicate<PathObject> upperAnnotation = { s -> s.getLevel() == 1};
QPEx.deselectAll()
PathROIToolsAwt.CombineOp opAdd = PathROIToolsAwt.CombineOp.ADD
PathROIToolsAwt.CombineOp opSub = PathROIToolsAwt.CombineOp.SUBTRACT
//Select and Tile Stroma
hierarchy = QPEx.getCurrentHierarchy();
for( Object in QPEx.getObjects(upperAnnotation))
{
        Collection<PathObject> addList = new ArrayList( Object.getChildObjects() )   //List is not modifiable and we need this to add the parent for subtraction
        print(addList.size().toString())
        PathObjectToolsAwt.combineAnnotations(hierarchy, addList, opAdd )
        Collection<PathObject> mergedSetList = new ArrayList( Object.getChildObjects() )
        print(mergedSetList.size().toString())
        PathAnnotationObject mergedSet = mergedSetList.first()
        mergedSet.setLocked(true)
        Collection<PathObject> subList = new ArrayList()
        subList.add(mergedSet)
        subList.add(Object)
        PathObjectToolsAwt.combineAnnotations(hierarchy, subList, opSub )
}


//At this point there should only be stroma areas remaing, we will use the same filter to get them again.


QPEx.selectObjects(upperAnnotation)
QPEx.runPlugin('qupath.lib.algorithms.TilerPlugin', '{"tileSizeMicrons": 50.0 ,  "trimToROI": true,  "makeAnnotations": false,  "removeParentAnnotation": false}');
QPEx.deselectAll()

for( Object in QPEx.getObjects(upperAnnotation) )
{
   for( Child in Object.getChildObjects() )
      {
          if( Child.getROI().getBoundsWidth() == 100 && Child.getROI().getBoundsHeight() == 100 )
           {
                
                frame = Child.getDisplayedName();
                RegionRequest request = RegionRequest.createInstance(path, downsample, Child.getROI())
                String name = String.format("normal_%s%s",frame,ext)
                File file1 = new File(subdirNormal, name)
                ImageWriterTools.writeImageRegionWithOverlay(imageData, overlayOptions, request, file1.getAbsolutePath())
                      
           }  
             
      }
}

logger.println("test data export complete")