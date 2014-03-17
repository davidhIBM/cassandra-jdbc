/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cassandra.cql.jdbc;

import static org.apache.cassandra.cql.jdbc.Utils.NO_RESULTSET;
import static org.apache.cassandra.cql.jdbc.Utils.NO_SERVER;
import static org.apache.cassandra.cql.jdbc.Utils.NO_UPDATE_COUNT;
import static org.apache.cassandra.cql.jdbc.Utils.SCHEMA_MISMATCH;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.ParseException;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.exceptions.QueryExecutionException;
import com.datastax.driver.core.exceptions.QueryValidationException;
import com.datastax.driver.core.exceptions.UnavailableException;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CassandraPreparedStatement extends CassandraStatement implements PreparedStatement
{
    private static final Logger LOG = LoggerFactory.getLogger(CassandraPreparedStatement.class);

    /** the key token passed back from server-side to identify the prepared statement */
    private com.datastax.driver.core.PreparedStatement preparedStatement;

    /** the count of bound variable markers (?) encountered in the parse o the CQL server-side */
    private int count;

    /** a Map of the current bound values encountered in setXXX methods */
    private Map<Integer, Object> bindValues = new LinkedHashMap<Integer, Object>();

    private boolean isDML;


    CassandraPreparedStatement(CassandraConnection con, String cql) throws SQLException
    {
        this(con, cql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }


    
    CassandraPreparedStatement(CassandraConnection con,
        String cql,
        int rsType,
        int rsConcurrency,
        int rsHoldability
        ) throws SQLException
    {
       super(con,cql,rsType,rsConcurrency,rsHoldability);
       if (LOG.isTraceEnabled()) LOG.trace("Preparing CQL: " + this.cql);
       try
       {
           com.datastax.driver.core.PreparedStatement result = con.prepare(cql, consistencyLevel);

           if (LOG.isTraceEnabled()) LOG.trace("Prepared ID: " + result + "CQL: " + this.cql);

           preparedStatement = result;   // Set it here, in case we throw
           count = result.getVariables().size();
           isDML = Utils.isDML(cql);
       }

       // TODO - Fix this
//       catch (InvalidRequestException e)
//       {
//           throw new SQLSyntaxErrorException(e);
//       }
//       catch (TException e)
//       {
//           throw new SQLNonTransientConnectionException(e);
//       }   //TODO - remove this
       catch (Exception e)
       {
         e.printStackTrace();
       }
    }
    
    String getCql()
    {
        return cql;
    }

    private void checkIndex(int index) throws SQLException
    {
        if (index > count) throw new SQLRecoverableException(String.format("the column index : %d is greater than the count of bound variable markers in the CQL: %d",
            index,
            count));
        if (index < 1) throw new SQLRecoverableException(String.format("the column index must be a positive number : %d", index));
    }

    public void close()
    {
      // TODO - added the check to avoid NPE, but why is this null?
      if (connection != null) {
        connection.removeStatement(this);
        connection = null;
      }
    }

    private void doExecute() throws SQLException
    {
        try
        {
            if (LOG.isTraceEnabled()) LOG.trace("Executing: " + preparedStatement);
            resetResults();
            com.datastax.driver.core.ResultSet result = connection.execute(preparedStatement, bindValues, consistencyLevel);

            if (isDML) {
              currentResultSet = null;
              updateCount = 1;
            } else {
              currentResultSet = new CassandraResultSet(this,result);

            }

        }
        catch (NoHostAvailableException e)
        {
            throw new SQLNonTransientConnectionException(NO_SERVER, e);
        }
        catch (QueryValidationException e)
        {
            throw new SQLSyntaxErrorException(e.getMessage() + "\n'" + cql + "'", e);
        }
        catch (QueryExecutionException e)
        {
            throw new SQLTransientConnectionException(e);
        }
    }

    public void addBatch() throws SQLException
    {
        throw new SQLFeatureNotSupportedException(NOT_SUPPORTED);
    }


    public void clearParameters() throws SQLException
    {
        checkNotClosed();
        bindValues.clear();
    }


    public boolean execute() throws SQLException
    {
        checkNotClosed();
        doExecute();
        return !(currentResultSet == null);
    }


    public ResultSet executeQuery() throws SQLException
    {
        checkNotClosed();
        doExecute();
        if (currentResultSet == null) throw new SQLNonTransientException(NO_RESULTSET);
        return currentResultSet;
    }


    public int executeUpdate() throws SQLException
    {
        checkNotClosed();
        doExecute();
        if (currentResultSet != null) throw new SQLNonTransientException(NO_UPDATE_COUNT);
        return updateCount;
    }


    public ResultSetMetaData getMetaData() throws SQLException
    {
        throw new SQLFeatureNotSupportedException(NOT_SUPPORTED);
    }


    public ParameterMetaData getParameterMetaData() throws SQLException
    {
        throw new SQLFeatureNotSupportedException(NOT_SUPPORTED);
    }


    public void setBigDecimal(int parameterIndex, BigDecimal decimal) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, decimal);
    }


    public void setBoolean(int parameterIndex, boolean truth) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, truth);
    }


    public void setByte(int parameterIndex, byte b) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, b);
    }


    public void setBytes(int parameterIndex, byte[] bytes) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, bytes);
    }


    public void setDate(int parameterIndex, Date value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // date type data is handled as an 8 byte Long value of milliseconds since the epoch (handled in decompose() )
        bindValues.put(parameterIndex, value);
    }


    public void setDate(int parameterIndex, Date date, Calendar cal) throws SQLException
    {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setDate(parameterIndex, date);
    }


    public void setDouble(int parameterIndex, double decimal) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, decimal);
    }


    public void setFloat(int parameterIndex, float decimal) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, decimal);
    }


    public void setInt(int parameterIndex, int integer) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, integer);
    }


    public void setLong(int parameterIndex, long bigint) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, bigint);
    }


    public void setNString(int parameterIndex, String value) throws SQLException
    {
        // treat like a String
        setString(parameterIndex, value);
    }


    public void setNull(int parameterIndex, int sqlType) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // silently ignore type for cassandra... just store an empty String
        bindValues.put(parameterIndex, null);
    }


    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException
    {
        // silently ignore type and type name for cassandra... just store an empty BB
        setNull(parameterIndex, sqlType);
    }


    public void setObject(int parameterIndex, Object object) throws SQLException
    {
        // For now all objects are forced to String type
        setObject(parameterIndex, object, Types.VARCHAR, 0);
    }

    public void setObject(int parameterIndex, Object object, int targetSqlType) throws SQLException
    {
        setObject(parameterIndex, object, targetSqlType, 0);
    }

    public final void setObject(int parameterIndex, Object object, int targetSqlType, int scaleOrLength) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);

        if (targetSqlType == Types.FLOAT) {
          if (object.getClass() == String.class) {
            object = Float.parseFloat((String) object);
          }
        }
        if (targetSqlType == Types.INTEGER) {
          if (object.getClass() == String.class) {
            object = Integer.parseInt((String) object);
          }
        }

        if (targetSqlType == Types.TIMESTAMP) {
          if (object.getClass() == String.class) {
           // TODO - we need fancier date parsing - handle more than just C* format
            try {
              object = Utils.CassandraDateFormat.parse((String)object) ;
            } catch (ParseException e) {
              throw new SQLSyntaxErrorException("Date Format '" + object + "' Invalid. Requires \"" + Utils.CASSANDRA_DATE_FORMAT_STRING + "\".");
            }
          }
        }
        // TODO - convert the types here
        bindValues.put(parameterIndex, object);
    }

    public void setRowId(int parameterIndex, RowId value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, value);
    }


    public void setShort(int parameterIndex, short smallint) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, smallint);
    }


    public void setString(int parameterIndex, String value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, value);
    }


    public void setTime(int parameterIndex, Time value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // time type data is handled as an 8 byte Long value of milliseconds since the epoch
        bindValues.put(parameterIndex,
            value == null ? null : new Date(value.getTime()));
    }


    public void setTime(int parameterIndex, Time value, Calendar cal) throws SQLException
    {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setTime(parameterIndex, value);
    }


    public void setTimestamp(int parameterIndex, Timestamp value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // timestamp type data is handled as an 8 byte Long value of milliseconds since the epoch. Nanos are not supported and are ignored
        bindValues.put(parameterIndex, value);
    }


    public void setTimestamp(int parameterIndex, Timestamp value, Calendar cal) throws SQLException
    {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setTimestamp(parameterIndex, value);
    }


    public void setURL(int parameterIndex, URL value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // URl type data is handled as an string
        String url = value.toString();
        bindValues.put(parameterIndex, url);
    }
}
