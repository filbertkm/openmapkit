# OpenMapKit

## Description

Android mobile application for browsing OpenStreetMap features to create and edit OSM tags.

## Main Features
* View OpenStreetMap tiles via web and pre-cached mbtiles.
* Download OSM data from the [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API) for offline use.
* Create and update tags for OSM features.
  - Data saved on device as [JOSM compatible OpenStreetMap XML](http://wiki.openstreetmap.org/wiki/JOSM_file_format).
  - Can be submitted to OpenMapKit Server or directly to [OpenStreetMap API](http://wiki.openstreetmap.org/wiki/API_v0.6).
* Integration with [ODK Collect](https://opendatakit.org/use/collect/).  
  - Can be launched by your XForm survey to select OSM features and create/edit tags within your survey collection workflow. 
  - Tags and OSM ID are sent back to ODK Collect to be stored in survey data


![OpenMapKit Flowchart](/docs/assets/OpenMapKit_flowchart.png)

###License (GNU)

See license [here](https://github.com/AmericanRedCross/openmapkit/blob/master/LICENSE.md).




