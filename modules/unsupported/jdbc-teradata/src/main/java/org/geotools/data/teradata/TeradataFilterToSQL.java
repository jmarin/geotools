/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2009, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.data.teradata;

import java.io.IOException;
import java.text.MessageFormat;

import org.geotools.data.jdbc.FilterToSQL;
import org.geotools.filter.FilterCapabilities;
import org.geotools.jdbc.SQLDialect;
import org.opengis.filter.expression.Literal;
import org.opengis.filter.expression.PropertyName;
import org.opengis.filter.spatial.BBOX;
import org.opengis.filter.spatial.Beyond;
import org.opengis.filter.spatial.BinarySpatialOperator;
import org.opengis.filter.spatial.Contains;
import org.opengis.filter.spatial.Crosses;
import org.opengis.filter.spatial.DWithin;
import org.opengis.filter.spatial.Disjoint;
import org.opengis.filter.spatial.DistanceBufferOperator;
import org.opengis.filter.spatial.Equals;
import org.opengis.filter.spatial.Intersects;
import org.opengis.filter.spatial.Overlaps;
import org.opengis.filter.spatial.Touches;
import org.opengis.filter.spatial.Within;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LinearRing;

public class TeradataFilterToSQL extends FilterToSQL {

	private TeradataGISDialect mDialect;
    boolean mLooseBBOXEnabled;
    
    public TeradataFilterToSQL(TeradataGISDialect dialect) {
    	mDialect = dialect;
    }

    public boolean isLooseBBOXEnabled() {
        return mLooseBBOXEnabled;
    }

    public void setLooseBBOXEnabled(boolean looseBBOXEnabled) {
    	mLooseBBOXEnabled = looseBBOXEnabled;
    }

    protected void visitLiteralGeometry(Literal expression) throws IOException {
        // evaluate the literal and store it for later
        Geometry geom = (Geometry) evaluateLiteral(expression, Geometry.class);

        if (geom instanceof LinearRing) {
            //teradata does not handle linear rings, convert to just a line string
            geom = geom.getFactory().createLineString(((LinearRing) geom).getCoordinateSequence());
        }

        out.write("'");
        out.write(geom.toText());
        out.write("'");
    }
    
    @Override
    protected FilterCapabilities createFilterCapabilities() { 
        FilterCapabilities caps = new FilterCapabilities();
        caps.addAll(SQLDialect.BASE_DBMS_CAPABILITIES);

        // adding the spatial filters support
        caps.addType(BBOX.class);
        caps.addType(Contains.class);
        caps.addType(Crosses.class);
        caps.addType(Disjoint.class);
        caps.addType(Equals.class);
        caps.addType(Intersects.class);
        caps.addType(Overlaps.class);
        caps.addType(Touches.class);
        caps.addType(Within.class);
        caps.addType(DWithin.class);
        caps.addType(Beyond.class);

        return caps;
    }
    
    protected Object visitBinarySpatialOperator(BinarySpatialOperator filter,
            PropertyName property, Literal geometry, boolean swapped,
            Object extraData) {
        try {
            if (filter instanceof DistanceBufferOperator) {
                visitDistanceSpatialOperator((DistanceBufferOperator) filter,
                        property, geometry, swapped, extraData);
            } else {
                visitComparisonSpatialOperator(filter, property, geometry,
                        swapped, extraData);
            }
        } catch (IOException e) {
            throw new RuntimeException(IO_ERROR, e);
        }
        return extraData;
    }
    
    void visitDistanceSpatialOperator(DistanceBufferOperator filter,
            PropertyName property, Literal geometry, boolean swapped,
            Object extraData) throws IOException {

    	if ((filter instanceof DWithin && !swapped)
                || (filter instanceof Beyond && swapped)) {
    		addTessellateIndex(property, geometry);
    		
    		property.accept(this, extraData);
            out.write(".");
            out.write("ST_DWithin(SYSSPATIAL.ST_GEOMFROMTEXT(");
            geometry.accept(this, extraData);
            out.write(",");
            out.write(Double.toString(filter.getDistance()));
            out.write("))");
        }
        if ((filter instanceof DWithin && swapped)
                || (filter instanceof Beyond && !swapped)) {
    		addTessellateIndex(property, geometry);
    		
            property.accept(this, extraData);
            out.write(".");
            out.write("ST_Distance(SYSSPATIAL.ST_GEOMFROMTEXT(");
            geometry.accept(this, extraData);
            out.write(")) > ");
            out.write(Double.toString(filter.getDistance()));
        }
    }
    
    void visitComparisonSpatialOperator(BinarySpatialOperator filter,
            PropertyName property, Literal geometry, boolean swapped, Object extraData)
            throws IOException {
        
    	if (!(filter instanceof Disjoint)) {
    		addTessellateIndex(property, geometry);
    	}
    	
    	property.accept(this, extraData);
        out.write(".");
        
        if (filter instanceof Equals) {
            out.write("ST_Equals");
        } else if (filter instanceof Disjoint) {
            out.write("ST_Disjoint ");
        } else if (filter instanceof Intersects || filter instanceof BBOX) {
            out.write("ST_Intersects");
        } else if (filter instanceof Crosses) {
            out.write("ST_Crosses");
        } else if (filter instanceof Within) {
            if (swapped)
                out.write("ST_Contains");
            else
                out.write("ST_Within");
        } else if (filter instanceof Contains) {
            if (swapped)
                out.write("ST_Within");
            else
                out.write("ST_Contains");
        } else if (filter instanceof Overlaps) {
            out.write("ST_Overlaps");
        } else if (filter instanceof Touches) {
            out.write("ST_Touches");
        } else {
            throw new RuntimeException("Unsupported filter type " + filter.getClass());
        }

        out.write("(SYSSPATIAL.ST_GEOMFROMTEXT(");
        geometry.accept(this, extraData);
        out.write(")) = 1");
    }

	private void addTessellateIndex(PropertyName property, Literal geometry)
			throws IOException {
		String propertyName = property.getPropertyName();
		if (propertyName.length() == 0) {
			propertyName = "geom";
		}
		
		String indexTableName = mDialect.getLastTableName() + "_" + propertyName + "_idx";
		if (!mDialect.indexTableExists("indexTableName")) {
			return;
		}
		
		StringBuffer sb = new StringBuffer();
		if (mDialect.getLastSchemaName() != null) {
			mDialect.encodeSchemaName(mDialect.getLastSchemaName(), sb);
			sb.append(".");
		}
		mDialect.encodeTableName(mDialect.getLastTableName(), sb);
		String encodedTableName = sb.toString();
		
		sb = new StringBuffer();
		if (mDialect.getLastSchemaName() != null) {
			mDialect.encodeSchemaName(mDialect.getLastSchemaName(), sb);
			sb.append(".");
		}
		mDialect.encodeTableName(indexTableName, sb);
		String encodedIdxTableName = sb.toString();

		sb = new StringBuffer();
		mDialect.encodeColumnName(mDialect.getKey(), sb);
		String encodedKeyName = sb.toString();
		
		Envelope env = ((Geometry)geometry.getValue()).getEnvelopeInternal();
		out.write(MessageFormat.format("{2} IN (SELECT DISTINCT ti.id " +
				"FROM {0} ti, TABLE (SYSSPATIAL.tessellate_search(1,"
				+ "  {12,number,0.0#}, {13,number,0.0#}, {14,number,0.0#}, {15,number,0.0#}, "
				+ "  {3,number,0.0#}, {4,number,0.0#}, {5,number,0.0#}, {6,number,0.0#}, "
				+ "  {7,number,0}, {8,number,0}, {9,number,0}, {10,number,0.0#}, {11,number,0})) AS i "
				+ "WHERE ti.cellid = i.cellid) AND ",
				encodedIdxTableName, encodedTableName, encodedKeyName, 
		mDialect.getU_xmin(), mDialect.getU_ymin(), mDialect.getU_xmax(), mDialect.getU_ymax(),
		mDialect.getG_nx(), mDialect.getG_ny(), mDialect.getLevels(), mDialect.getScale(), mDialect.getShift(),
		env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY()));
	}
}