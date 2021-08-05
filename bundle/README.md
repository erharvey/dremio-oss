# OSGi Bundle for Dremio JDBC Driver

`dremio_jdbc_driver_15.7.0_202106112202490252_2fb62d31_1.0.0` is an OSGi bundle for Dremio JDBC Driver that can be used with WSO2 DSS. In order to use it, copy the file to <DSS_PRODUCT_HOME>/repository/components/dropins and restart DSS.

## DSS Datasource Configuration

A DSS data source can be connected to Dremio by configuring the following properties:

* Datasource Type: `RDBMS`
* Database Engine: `Generic`
* Driver Class: `com.dremio.jdbc.Driver`
* URL: `jdbc:dremio:direct=localhost:31010`
* User Name: `<dremio_username>`
* Password: `<dremio_password>`

## Steps to Recreate the Bundle

1. Either download the Dremio JDBC Driver JAR file from the Dremio website or get it from <DREMIO_HOME>/jars/jdbc-driver/ if you have a Dremio installation

2. Copy the driver JAR file into <DSS_PRODUCT_HOME>/repository/components/lib and restart DSS; an OSGi bundle will be automatically created and placed inside <DSS_PRODUCT_HOME>/repository/components/dropins

3. In order to avoid conflicts between the version of SLF4J logging API used by DSS and the one used by the driver, you can prevent the driver from importing packages from DSS: open the MANIFEST.MF file of the newly created bundle and update it by removing the line `DynamicImport-Package: *`

4. Remove the driver JAR file from <DSS_PRODUCT_HOME>/repository/components/lib (otherwise your update to the bundle will be overridden) and restart DSS

When you create a datasource that connects to Dremio, you will likely get a warning on the DSS console that a default logger will be used for the driver logs.