package org.codehaus.plexus.util.cli;

/*
 * The MIT License
 *
 * Copyright (c) 2004, The Codehaus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Vector;

import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.StringUtils;

/**
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l </a>
 * @version $Id$
 */
public abstract class CommandLineUtils
{
    private static Set processes = Collections.synchronizedSet( new HashSet() );

    static
    {
        Runtime.getRuntime().addShutdownHook( new Thread( "CommandlineUtil shutdown" )
        {
            public void run()
            {
                if ( processes != null && processes.size() > 0 )
                {
                    System.err.println( "Destroying " + processes.size() + " processes" );
                    for ( Iterator it = processes.iterator(); it.hasNext(); )
                    {
                        System.err.println( "Destroying process.." );
                        ( (Process) it.next() ).destroy();

                    }
                    System.err.println( "Destroyed " + processes.size() + " processes" );
                }
            }
        } );
    }

    public static class StringStreamConsumer
        implements StreamConsumer
    {
        private StringBuffer string = new StringBuffer();

        private String ls = System.getProperty( "line.separator" );

        public void consumeLine( String line )
        {
            string.append( line + ls );
        }

        public String getOutput()
        {
            return string.toString();
        }
    }

    public static int executeCommandLine( Commandline cl, StreamConsumer systemOut, StreamConsumer systemErr )
        throws CommandLineException
    {
        return executeCommandLine( cl, null, systemOut, systemErr, 0 );
    }

    public static int executeCommandLine( Commandline cl, StreamConsumer systemOut, StreamConsumer systemErr, int timeoutInSeconds )
        throws CommandLineException
    {
        return executeCommandLine( cl, null, systemOut, systemErr, timeoutInSeconds );
    }

    public static int executeCommandLine( Commandline cl, InputStream systemIn, StreamConsumer systemOut, StreamConsumer systemErr )
        throws CommandLineException
    {
        return executeCommandLine( cl, systemIn, systemOut, systemErr, 0 );
    }

    public static int executeCommandLine( Commandline cl, InputStream systemIn, StreamConsumer systemOut, StreamConsumer systemErr, int timeoutInSeconds )
        throws CommandLineException
    {
        if ( cl == null )
        {
            throw new IllegalArgumentException( "cl cannot be null." );
        }

        Process p;

        try {
        	p = Runtime.getRuntime().exec(cl.getCommandline(), cl.getEnvironmentVariables(), cl.getWorkingDirectory());
        } catch (IOException e) {
        	throw new CommandLineException("Failed to start process", e);
        }

        processes.add( p );

        StreamFeeder inputFeeder = null;

        if ( systemIn != null )
        {
            inputFeeder = new StreamFeeder( systemIn, p.getOutputStream() );
        }

        StreamPumper outputPumper = new StreamPumper( p.getInputStream(), systemOut );

        StreamPumper errorPumper = new StreamPumper( p.getErrorStream(), systemErr );

        if ( inputFeeder != null )
        {
            inputFeeder.start();
        }

        outputPumper.start();

        errorPumper.start();

        try
        {
            int returnValue;
            if ( timeoutInSeconds <= 0 )
            {
                returnValue = p.waitFor();
            }
            else
            {
                long now = System.currentTimeMillis();
                long timeoutInMillis = 1000L * timeoutInSeconds;
                long finish = now + timeoutInMillis;
                while ( isAlive( p ) && System.currentTimeMillis() < finish )
                {
                    Thread.sleep( 10 );
                }
                if ( isAlive( p ) )
                {
                    throw new InterruptedException( "Process timeout out after " + timeoutInSeconds + " seconds" );
                }
                returnValue = p.exitValue();
            }

            if ( inputFeeder != null )
            {
                synchronized ( inputFeeder )
                {
                    if ( !inputFeeder.isDone() )
                    {
                        inputFeeder.wait();
                    }
                }
            }

            synchronized ( outputPumper )
            {
                if ( !outputPumper.isDone() )
                {
                    outputPumper.wait();
                }
            }

            synchronized ( errorPumper )
            {
                if ( !errorPumper.isDone() )
                {
                    errorPumper.wait();
                }
            }

            processes.remove( p );

            return returnValue;
        }
        catch ( InterruptedException ex )
        {
            killProcess( p );
            throw new CommandLineException( "Error while executing external command, process killed.", ex );
        }
        finally
        {
            if ( inputFeeder != null )
            {
                inputFeeder.close();
            }

            outputPumper.close();

            errorPumper.close();
        }
    }

    public static Properties getSystemEnvVars()
        throws IOException
    {
        return getSystemEnvVars( true );
    }

    /**
     * Return the shell environment variables. If <code>caseSensitive == true</code>, then envar
     * keys will all be upper-case.
     *
     * @param caseSensitive Whether environment variable keys should be treated case-sensitively.
     * @return Properties object of (possibly modified) envar keys mapped to their values.
     * @throws IOException
     */
    public static Properties getSystemEnvVars( boolean caseSensitive )
        throws IOException
    {
        Process p = null;

        Properties envVars = new Properties();

        Runtime r = Runtime.getRuntime();

        //If this is windows set the shell to command.com or cmd.exe with correct arguments.
        if ( Os.isFamily( Os.FAMILY_WINDOWS ) )
        {
            if ( Os.isFamily( Os.FAMILY_WIN9X ) )
            {
                p = r.exec( "command.com /c set" );
            }
            else
            {
                p = r.exec( "cmd.exe /c set" );
            }
        }
        else
        {
            p = r.exec( "env" );
        }

        BufferedReader br = new BufferedReader( new InputStreamReader( p.getInputStream() ) );

        String line;

        String lastKey = null;
        String lastVal = null;

        while ( ( line = br.readLine() ) != null )
        {
            int idx = line.indexOf( '=' );

            if ( idx > 0 )
            {
                lastKey = line.substring( 0, idx );

                if ( !caseSensitive )
                {
                    lastKey = lastKey.toUpperCase();
                }

                lastVal = line.substring( idx + 1 );

                envVars.setProperty( lastKey, lastVal );
            }
            else if ( lastKey != null )
            {
                lastVal += "\n" + line;

                envVars.setProperty( lastKey, lastVal );
            }
        }

        return envVars;
    }

    /**
     * Kill a process launched by executeCommandLine methods
     *
     * @param p The process to kill
     */
    public static void killProcess( Process p )
    {
        if ( p != null )
        {
            p.destroy();
            System.out.println( "killed." );
            processes.remove( p );
        }
        else
        {
            System.out.println( "don't exist." );
        }
    }

    public static boolean isAlive( Process p ) {
        try
        {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public static String[] translateCommandline( String toProcess )
        throws Exception
    {
        if ( toProcess == null || toProcess.length() == 0 )
        {
            return new String[0];
        }

        // parse with a simple finite state machine

        final int normal = 0;
        final int inQuote = 1;
        final int inDoubleQuote = 2;
        int state = normal;
        StringTokenizer tok = new StringTokenizer( toProcess, "\"\' ", true );
        Vector v = new Vector();
        StringBuffer current = new StringBuffer();

        while ( tok.hasMoreTokens() )
        {
            String nextTok = tok.nextToken();
            switch ( state )
            {
                case inQuote:
                    if ( "\'".equals( nextTok ) )
                    {
                        state = normal;
                    }
                    else
                    {
                        current.append( nextTok );
                    }
                    break;
                case inDoubleQuote:
                    if ( "\"".equals( nextTok ) )
                    {
                        state = normal;
                    }
                    else
                    {
                        current.append( nextTok );
                    }
                    break;
                default:
                    if ( "\'".equals( nextTok ) )
                    {
                        state = inQuote;
                    }
                    else if ( "\"".equals( nextTok ) )
                    {
                        state = inDoubleQuote;
                    }
                    else if ( " ".equals( nextTok ) )
                    {
                        if ( current.length() != 0 )
                        {
                            v.addElement( current.toString() );
                            current.setLength( 0 );
                        }
                    }
                    else
                    {
                        current.append( nextTok );
                    }
                    break;
            }
        }

        if ( current.length() != 0 )
        {
            v.addElement( current.toString() );
        }

        if ( state == inQuote || state == inDoubleQuote )
        {
            throw new CommandLineException( "unbalanced quotes in " + toProcess );
        }

        String[] args = new String[v.size()];
        v.copyInto( args );
        return args;
    }

    /**
     * <p>Put quotes around the given String if necessary.</p>
     * <p>If the argument doesn't include spaces or quotes, return it
     * as is. If it contains double quotes, use single quotes - else
     * surround the argument by double quotes.</p>
     *
     * @throws CommandLineException if the argument contains both, single
     *                              and double quotes.
     *
     * @deprecated Use {@link StringUtils#quoteAndEscape(String, char, char[], char[], char, boolean)},
     * {@link StringUtils#quoteAndEscape(String, char, char[], char, boolean)}, or
     * {@link StringUtils#quoteAndEscape(String, char)} instead.
     */
    public static String quote( String argument )
        throws CommandLineException
    {
        return quote( argument, false, false, true );
    }

    /**
     * <p>Put quotes around the given String if necessary.</p>
     * <p>If the argument doesn't include spaces or quotes, return it
     * as is. If it contains double quotes, use single quotes - else
     * surround the argument by double quotes.</p>
     *
     * @throws CommandLineException if the argument contains both, single
     *                              and double quotes.
     *
     * @deprecated Use {@link StringUtils#quoteAndEscape(String, char, char[], char[], char, boolean)},
     * {@link StringUtils#quoteAndEscape(String, char, char[], char, boolean)}, or
     * {@link StringUtils#quoteAndEscape(String, char)} instead.
     */
    public static String quote( String argument, boolean wrapExistingQuotes )
        throws CommandLineException
    {
        return quote( argument, false, false, wrapExistingQuotes );
    }

    /**
     * @deprecated Use {@link StringUtils#quoteAndEscape(String, char, char[], char[], char, boolean)},
     * {@link StringUtils#quoteAndEscape(String, char, char[], char, boolean)}, or
     * {@link StringUtils#quoteAndEscape(String, char)} instead.
     */
    public static String quote( String argument, boolean escapeSingleQuotes, boolean escapeDoubleQuotes,
                                boolean wrapExistingQuotes )
        throws CommandLineException
    {
        if ( argument.indexOf( "\"" ) > -1 )
        {
            if ( argument.indexOf( "\'" ) > -1 )
            {
                throw new CommandLineException( "Can't handle single and double quotes in same argument" );
            }
            else
            {
                if ( escapeSingleQuotes )
                {
                    return "\\\'" + argument + "\\\'";
                }
                else if ( wrapExistingQuotes )
                {
                    return '\'' + argument + '\'';
                }
            }
        }
        else if ( argument.indexOf( "\'" ) > -1 )
        {
            if ( escapeDoubleQuotes )
            {
                return "\\\"" + argument + "\\\"";
            }
            else if ( wrapExistingQuotes )
            {
                return '\"' + argument + '\"';
            }
        }
        else if ( argument.indexOf( " " ) > -1 )
        {
            if ( escapeDoubleQuotes )
            {
                return "\\\"" + argument + "\\\"";
            }
            else
            {
                return '\"' + argument + '\"';
            }
        }

        return argument;
    }

    public static String toString( String[] line )
    {
        // empty path return empty string
        if ( line == null || line.length == 0 )
        {
            return "";
        }

        // path containing one or more elements
        final StringBuffer result = new StringBuffer();
        for ( int i = 0; i < line.length; i++ )
        {
            if ( i > 0 )
            {
                result.append( ' ' );
            }
            try
            {
                result.append( StringUtils.quoteAndEscape( line[i], '\"' ) );
            }
            catch ( Exception e )
            {
                System.err.println( "Error quoting argument: " + e.getMessage() );
            }
        }
        return result.toString();
    }

}
