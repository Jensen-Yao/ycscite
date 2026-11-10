# Optional journal basemaps

The default dual-map view is built from the input records and their cited journals. It does not depend on any bundled CiteSpace or JCR files.

For a journal basemap you are entitled to use, set `YCSCITE_JCR_HOME` to a directory containing `citing.csv` and `cited.csv`, then restart Ycscite. The CSV reader expects columns `ID,CITEDJ,X,Y,WEIGHT,CLUSTER,TITLE,RED,GREEN,BLUE,NPUBL,BLONDEL`. Quoted CSV fields are supported. The resulting graph reports the actual number of parsed map points.

Local historical CSVs in this development workspace are reference assets, not product-owned data. They are not included in the public source repository or release package.
