# Optional journal basemaps

The default journal-flow view is built from the input records and their cited journals. It uses the journal relationships observed in the imported data; a journal basemap is optional.

For a journal basemap you are entitled to use, set `YCSCITE_JCR_HOME` to a directory containing `citing.csv` and `cited.csv`, then restart Ycscite. The CSV reader expects columns `ID,CITEDJ,X,Y,WEIGHT,CLUSTER,TITLE,RED,GREEN,BLUE,NPUBL,BLONDEL`. Quoted CSV fields are supported. The resulting graph reports the actual number of parsed map points.

Use a basemap whose license permits your intended use. Basemap files are supplied and configured by the user.
