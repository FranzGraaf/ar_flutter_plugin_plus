import 'package:ar_flutter_plugin_plus/managers/ar_location_manager.dart';
import 'package:ar_flutter_plugin_plus/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_plus/managers/ar_object_manager.dart';
import 'package:ar_flutter_plugin_plus/managers/ar_anchor_manager.dart';
import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_plus/ar_flutter_plugin_plus.dart';
import 'package:ar_flutter_plugin_plus/datatypes/config_planedetection.dart';

class DebugOptionsWidget extends StatefulWidget {
  DebugOptionsWidget({Key? key}) : super(key: key);
  @override
  _DebugOptionsWidgetState createState() => _DebugOptionsWidgetState();
}

class _DebugOptionsWidgetState extends State<DebugOptionsWidget> {
  ARSessionManager? arSessionManager;
  ARObjectManager? arObjectManager;
  bool _showFeaturePoints = false;
  bool _showPlanes = false;
  bool _showWorldOrigin = false;
  bool _showAnimatedGuide = true;
  String _planeTexturePath = "Images/triangle.png";
  bool _handleTaps = false;
  late final Widget _arView;

  @override
  void initState() {
    super.initState();
    _arView = ARView(
      key: const ValueKey('debug_ar_view'),
      onARViewCreated: onARViewCreated,
      planeDetectionConfig: PlaneDetectionConfig.horizontalAndVertical,
      showPlatformType: true,
    );
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
          title: const Text('Debug Options'),
        ),
        body: SafeArea(
          child: Container(
              child: Stack(children: [
            _arView,
            Align(
              alignment: FractionalOffset.bottomRight,
              child: Container(
                width: MediaQuery.of(context).size.width * 0.5,
                color: Color(0xFFFFFFF).withValues(alpha: 0.5),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    /*Padding(
                      padding: const EdgeInsets.only(right: 12, top: 8),
                      child: Text(
                        'Tracking: $_trackingState ($_trackingReason)',
                        style: const TextStyle(fontSize: 12),
                      ),
                    ),*/
                    SwitchListTile(
                      title: const Text('Feature Points'),
                      value: _showFeaturePoints,
                      onChanged: (bool value) {
                        setState(() {
                          _showFeaturePoints = value;
                          updateSessionSettings();
                        });
                      },
                    ),
                    SwitchListTile(
                      title: const Text('Planes'),
                      value: _showPlanes,
                      onChanged: (bool value) {
                        setState(() {
                          _showPlanes = value;
                          updateSessionSettings();
                        });
                      },
                    ),
                    SwitchListTile(
                      title: const Text('World Origin'),
                      value: _showWorldOrigin,
                      onChanged: (bool value) {
                        setState(() {
                          _showWorldOrigin = value;
                          updateSessionSettings();
                        });
                      },
                    ),
                  ],
                ),
              ),
            ),
          ])),
        ));
  }

  void onARViewCreated(
      ARSessionManager arSessionManager,
      ARObjectManager arObjectManager,
      ARAnchorManager arAnchorManager,
      ARLocationManager arLocationManager) {
    this.arSessionManager = arSessionManager;
    this.arObjectManager = arObjectManager;
    this.arSessionManager!.onTrackingStateChanged = (state, reason) {
      if (mounted) {
        setState(() {});
      }
    };

    this.arSessionManager!.onInitialize(
          showFeaturePoints: _showFeaturePoints,
          showPlanes: _showPlanes,
          customPlaneTexturePath: _planeTexturePath,
          showWorldOrigin: _showWorldOrigin,
          showAnimatedGuide: _showAnimatedGuide,
          handleTaps: _handleTaps,
        );
    this.arObjectManager!.onInitialize();
  }

  void updateSessionSettings() {
    this.arSessionManager!.onInitialize(
          showFeaturePoints: _showFeaturePoints,
          showPlanes: _showPlanes,
          customPlaneTexturePath: _planeTexturePath,
          showWorldOrigin: _showWorldOrigin,
        );
  }
}
