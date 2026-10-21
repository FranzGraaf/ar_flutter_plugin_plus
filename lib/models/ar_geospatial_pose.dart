/// Geospatial pose of the camera as reported by the ARCore Geospatial API
class ARGeospatialPose {
  double? latitude;
  double? longitude;
  double? altitude;

  /// Rotation in the East-Up-South frame as [x, y, z, w]
  List<double?>? eastUpSouthQuaternion;
  double? horizontalAccuracy;
  double? orientationYawAccuracy;
  double? verticalAccuracy;

  ARGeospatialPose.fromMap(Map<dynamic, dynamic> map) {
    this.latitude = (map['latitude'] as num?)?.toDouble();
    this.longitude = (map['longitude'] as num?)?.toDouble();
    this.altitude = (map['altitude'] as num?)?.toDouble();
    final quaternion = map['eastUpSouthQuaternion'] as List<dynamic>?;
    this.eastUpSouthQuaternion =
        quaternion?.map((e) => (e as num?)?.toDouble()).toList();
    this.horizontalAccuracy = (map['horizontalAccuracy'] as num?)?.toDouble();
    this.orientationYawAccuracy =
        (map['orientationYawAccuracy'] as num?)?.toDouble();
    this.verticalAccuracy = (map['verticalAccuracy'] as num?)?.toDouble();
  }
}
