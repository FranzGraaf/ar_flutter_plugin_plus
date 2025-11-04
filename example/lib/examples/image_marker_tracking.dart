import 'package:ar_flutter_plugin_plus/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_plus/datatypes/node_types.dart';
import 'package:ar_flutter_plugin_plus/managers/ar_anchor_manager.dart';
import 'package:ar_flutter_plugin_plus/managers/ar_location_manager.dart';
import 'package:ar_flutter_plugin_plus/managers/ar_object_manager.dart';
import 'package:ar_flutter_plugin_plus/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_plus/models/ar_anchor.dart';
import 'package:ar_flutter_plugin_plus/models/ar_node.dart';
import 'package:ar_flutter_plugin_plus/widgets/ar_view.dart';
import 'package:flutter/material.dart';
import 'package:vector_math/vector_math_64.dart';

class ImageMarkerTracking extends StatefulWidget {
  const ImageMarkerTracking({Key? key}) : super(key: key);

  @override
  State<ImageMarkerTracking> createState() => _ImageMarkerTrackingState();
}

class _ImageMarkerTrackingState extends State<ImageMarkerTracking> {
  ARSessionManager? arSessionManager;
  ARObjectManager? arObjectManager;
  ARAnchorManager? arAnchorManager;
  ARLocationManager? arLocationManager;

  ARAnchor? anchor;
  ARNode? node;

  void onARViewCreated(
      ARSessionManager arSessionManager,
      ARObjectManager arObjectManager,
      ARAnchorManager arAnchorManager,
      ARLocationManager arLocationManager) {
    this.arSessionManager = arSessionManager;
    this.arObjectManager = arObjectManager;
    this.arAnchorManager = arAnchorManager;
    this.arLocationManager = arLocationManager;

    this.arSessionManager!.onInitialize(
      showFeaturePoints: false,
      showPlanes: false,
      customPlaneTexturePath: "Images/triangle.png",
      showWorldOrigin: false,
      handleTaps: false,
      trackingImagePaths: [
        "Images/augmented-images-earth.jpg",
      ],
    );
    this.arObjectManager!.onInitialize();
    this.arSessionManager!.onImageDetected = onImageDetected;
  }

  void onImageDetected(String imageName, Matrix4 transformation) {
    print("Image detected: $imageName");

    // Convert transformation matrix to position
    Vector3 position = transformation.getTranslation();
    print("Image '$imageName' detected at position: $position");

    // Automatically place an object on the detected image
    placeObjectOnImage(imageName, transformation);
  }

  Future<void> placeObjectOnImage(
      String imageName, Matrix4 transformation) async {
    try {
      // Create a new anchor at the image position
      var imageAnchor = ARPlaneAnchor(transformation: transformation);

      bool? didAddAnchor = await arAnchorManager!.addAnchor(imageAnchor);
      if (didAddAnchor == true) {
        // Remove any existing anchor and node
        if (anchor != null) {
          arAnchorManager?.removeAnchor(anchor!);
        }
        if (node != null) {
          arObjectManager?.removeNode(node!);
        }

        anchor = imageAnchor;

        var modelUrl = "Models/Chicken_01/Chicken_01.gltf";

        // Create a 3D object to place on the image
        double scale = 0.05;
        var imageNode = ARNode(
          type: NodeType.localGLTF2,
          uri: modelUrl,
          transformation: Matrix4(
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
            scale,
          ),
          position: Vector3(0.0, 0.0, 0.0),
          rotation: Vector4(1.0, 0.0, 0.0, 0.0),
        );

        bool? didAddNodeToAnchor =
            await arObjectManager!.addNode(imageNode, planeAnchor: imageAnchor);

        if (didAddNodeToAnchor == true) {
          node = imageNode;
          print("Successfully placed object on image: $imageName");
        } else {
          //arSessionManager!.onError("Adding Node to Image Anchor failed");
        }
      } else {
        //arSessionManager!.onError("Adding Image Anchor failed");
      }
    } catch (e) {
      print("Error placing object on image: $e");
    }
  }

  @override
  void dispose() {
    super.dispose();
    arSessionManager!.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Image Marker Tracking'),
      ),
      body: ARView(
        onARViewCreated: onARViewCreated,
        planeDetectionConfig: PlaneDetectionConfig.horizontalAndVertical,
      ),
    );
  }
}
