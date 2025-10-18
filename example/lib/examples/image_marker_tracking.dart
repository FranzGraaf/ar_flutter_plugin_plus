import 'dart:developer';

import 'package:ar_flutter_plugin/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin/datatypes/hittest_result_types.dart';
import 'package:ar_flutter_plugin/datatypes/node_types.dart';
import 'package:ar_flutter_plugin/managers/ar_anchor_manager.dart';
import 'package:ar_flutter_plugin/managers/ar_location_manager.dart';
import 'package:ar_flutter_plugin/managers/ar_object_manager.dart';
import 'package:ar_flutter_plugin/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin/models/ar_anchor.dart';
import 'package:ar_flutter_plugin/models/ar_hittest_result.dart';
import 'package:ar_flutter_plugin/models/ar_node.dart';
import 'package:ar_flutter_plugin/widgets/ar_view.dart';
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
          showPlanes: true,
          customPlaneTexturePath: "Images/triangle.png",
          showWorldOrigin: true,
          handleTaps: true,
        );
    this.arObjectManager!.onInitialize();
    this.arSessionManager!.onPlaneOrPointTap = onPlaneOrPointTapped;
  }

  Future<void> onPlaneOrPointTapped(
      List<ARHitTestResult> hitTestResults) async {
    try {
      ARHitTestResult singleHitTestResult = hitTestResults.firstWhere(
          (hitTestResult) => hitTestResult.type == ARHitTestResultType.plane);
      var newAnchor =
          ARPlaneAnchor(transformation: singleHitTestResult.worldTransform);
      bool? didAddAnchor = await arAnchorManager!.addAnchor(newAnchor);
      if (didAddAnchor != null && didAddAnchor) {
        // remove old anchor and node
        if (anchor != null) {
          arAnchorManager!.removeAnchor(anchor!);
        }
        if (node != null) {
          arObjectManager!.removeNode(node!);
        }
        // set current anchor
        anchor = newAnchor;
        // Add note to anchor
        var newNode = ARNode(
            type: NodeType.localGLTF2,
            uri:
                "Models/realistic_crystal.glb", //"Models/Chicken_01/Chicken_01.gltf",
            scale: Vector3(0.2, 0.2, 0.2),
            position: Vector3(0.0, 0.0, 0.0),
            rotation: Vector4(1.0, 0.0, 0.0, 0.0));
        bool? didAddNodeToAnchor =
            await arObjectManager!.addNode(newNode, planeAnchor: newAnchor);

        if (didAddNodeToAnchor != null && didAddNodeToAnchor) {
          node = newNode;
        } else {
          arSessionManager!.onError("Adding Node to Anchor failed");
        }
      } else {
        arSessionManager!.onError("Adding Anchor failed");
      }
    } catch (e) {
      // nothing just no plane found
      log(e.toString());
    }
  }

  @override
  void dispose() {
    super.dispose();
    arSessionManager!.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ARView(
      onARViewCreated: onARViewCreated,
      planeDetectionConfig: PlaneDetectionConfig.horizontalAndVertical,
    );
  }
}
