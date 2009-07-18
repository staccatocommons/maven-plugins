package org.apache.maven.plugin.javadoc;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang.SystemUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.apache.maven.shared.invoker.PrintStreamHandler;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

/**
 * Set of utilities methods for Javadoc.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 * @since 2.4
 */
public class JavadocUtil
{
    /**
     * Method that removes the invalid directories in the specified directories.
     * <b>Note</b>: All elements in <code>dirs</code> could be an absolute or relative against the project's base
     * directory <code>String</code> path.
     *
     * @param project the current Maven project not null
     * @param dirs the list of <code>String</code> directories path that will be validated.
     * @return a List of valid <code>String</code> directories absolute paths.
     */
    protected static List pruneDirs( MavenProject project, List dirs )
    {
        List pruned = new ArrayList( dirs.size() );
        for ( Iterator i = dirs.iterator(); i.hasNext(); )
        {
            String dir = (String) i.next();

            if ( dir == null )
            {
                continue;
            }

            File directory = new File( dir );
            if ( !directory.isAbsolute() )
            {
                directory = new File( project.getBasedir(), directory.getPath() );
            }

            if ( directory.isDirectory() && !pruned.contains( directory.getAbsolutePath() ) )
            {
                pruned.add( directory.getAbsolutePath() );
            }
        }

        return pruned;
    }

    /**
     * Method that removes the invalid files in the specified files.
     * <b>Note</b>: All elements in <code>files</code> should be an absolute <code>String</code> path.
     *
     * @param files the list of <code>String</code> files paths that will be validated.
     * @return a List of valid <code>File</code> objects.
     */
    protected static List pruneFiles( List files )
    {
        List pruned = new ArrayList( files.size() );
        for ( Iterator i = files.iterator(); i.hasNext(); )
        {
            String f = (String) i.next();

            if ( f == null )
            {
                continue;
            }

            File file = new File( f );
            if ( file.isFile() && !pruned.contains( f ) )
            {
                pruned.add( f );
            }
        }

        return pruned;
    }

    /**
     * Method that gets all the source files to be excluded from the javadoc on the given
     * source paths.
     *
     * @param sourcePaths      the path to the source files
     * @param subpackagesList  list of subpackages to be included in the javadoc
     * @param excludedPackages the package names to be excluded in the javadoc
     * @return a List of the source files to be excluded in the generated javadoc
     */
    protected static List getExcludedNames( List sourcePaths, String[] subpackagesList, String[] excludedPackages )
    {
        List excludedNames = new ArrayList();
        for ( Iterator i = sourcePaths.iterator(); i.hasNext(); )
        {
            String path = (String) i.next();
            for ( int j = 0; j < subpackagesList.length; j++ )
            {
                List excludes = getExcludedPackages( path, excludedPackages );
                excludedNames.addAll( excludes );
            }
        }

        return excludedNames;
    }

    /**
     * Copy from {@link org.apache.maven.project.MavenProject#getCompileArtifacts()}
     * @param artifacts not null
     * @return list of compile artifacts with compile scope
     * @deprecated since 2.5, using {@link #getCompileArtifacts(Set, boolean)} instead of.
     */
    protected static List getCompileArtifacts( Set artifacts )
    {
        return getCompileArtifacts( artifacts, false );
    }

    /**
     * Copy from {@link org.apache.maven.project.MavenProject#getCompileArtifacts()}
     * @param artifacts not null
     * @param withTestScope flag to include or not the artifacts with test scope
     * @return list of compile artifacts with or without test scope.
     */
    protected static List getCompileArtifacts( Set artifacts, boolean withTestScope )
    {
        List list = new ArrayList( artifacts.size() );

        for ( Iterator i = artifacts.iterator(); i.hasNext(); )
        {
            Artifact a = (Artifact) i.next();

            // TODO: classpath check doesn't belong here - that's the other method
            if ( a.getArtifactHandler().isAddedToClasspath() )
            {
                // TODO: let the scope handler deal with this
                if ( withTestScope )
                {
                    if ( Artifact.SCOPE_COMPILE.equals( a.getScope() )
                        || Artifact.SCOPE_PROVIDED.equals( a.getScope() )
                        || Artifact.SCOPE_SYSTEM.equals( a.getScope() )
                        || Artifact.SCOPE_TEST.equals( a.getScope() ) )
                    {
                        list.add( a );
                    }
                }
                else
                {
                    if ( Artifact.SCOPE_COMPILE.equals( a.getScope() ) || Artifact.SCOPE_PROVIDED.equals( a.getScope() )
                        || Artifact.SCOPE_SYSTEM.equals( a.getScope() ) )
                    {
                        list.add( a );
                    }
                }
            }
        }

        return list;
    }

    /**
     * Convenience method to wrap an argument value in single quotes (i.e. <code>'</code>). Intended for values
     * which may contain whitespaces.
     * <br/>
     * To prevent javadoc error, the line separator (i.e. <code>\n</code>) are skipped.
     *
     * @param value the argument value.
     * @return argument with quote
     */
    protected static String quotedArgument( String value )
    {
        String arg = value;

        if ( StringUtils.isNotEmpty( arg ) )
        {
            if ( arg.indexOf( "'" ) != -1 )
            {
                arg = StringUtils.replace( arg, "'", "\\'" );
            }
            arg = "'" + arg + "'";

            // To prevent javadoc error
            arg = StringUtils.replace( arg, "\n", " " );
        }

        return arg;
    }

    /**
     * Convenience method to format a path argument so that it is properly interpreted by the javadoc tool. Intended
     * for path values which may contain whitespaces.
     *
     * @param value the argument value.
     * @return path argument with quote
     */
    protected static String quotedPathArgument( String value )
    {
        String path = value;

        if ( StringUtils.isNotEmpty( path ) )
        {
            path = path.replace( '\\', '/' );
            if ( path.indexOf( "\'" ) != -1 )
            {
                String split[] = path.split( "\'" );
                path = "";

                for ( int i = 0; i < split.length; i++ )
                {
                    if ( i != split.length - 1 )
                    {
                        path = path + split[i] + "\\'";
                    }
                    else
                    {
                        path = path + split[i];
                    }
                }
            }
            path = "'" + path + "'";
        }

        return path;
    }

    /**
     * Convenience method that copy all <code>doc-files</code> directories from <code>javadocDir</code>
     * to the <code>outputDirectory</code>.
     *
     * @param outputDirectory the output directory
     * @param javadocDir the javadoc directory
     * @throws IOException if any
     * @deprecated since 2.5, using {@link #copyJavadocResources(File, File, String)} instead of.
     */
    protected static void copyJavadocResources( File outputDirectory, File javadocDir )
        throws IOException
    {
        copyJavadocResources( outputDirectory, javadocDir, null );
    }

    /**
     * Convenience method that copy all <code>doc-files</code> directories from <code>javadocDir</code>
     * to the <code>outputDirectory</code>.
     *
     * @param outputDirectory the output directory
     * @param javadocDir the javadoc directory
     * @param excludedocfilessubdir the excludedocfilessubdir parameter
     * @throws IOException if any
     * @since 2.5
     */
    protected static void copyJavadocResources( File outputDirectory, File javadocDir, String excludedocfilessubdir )
        throws IOException
    {
        List excludes = new ArrayList();
        excludes.addAll( Arrays.asList( FileUtils.getDefaultExcludes() ) );

        if ( StringUtils.isNotEmpty( excludedocfilessubdir ) )
        {
            StringTokenizer st = new StringTokenizer( excludedocfilessubdir, ":" );
            String current;
            while ( st.hasMoreTokens() )
            {
                current = st.nextToken();
                excludes.add( "**/" + current + "/**" );
            }
        }

        if ( javadocDir.exists() && javadocDir.isDirectory() )
        {
            List docFiles =
                FileUtils.getDirectoryNames( javadocDir, "**/doc-files", StringUtils.join( excludes.iterator(),
                                                                                           "," ), false, true );
            for ( Iterator it = docFiles.iterator(); it.hasNext(); )
            {
                String docFile = (String) it.next();

                File docFileOutput = new File( outputDirectory, docFile );
                FileUtils.mkdir( docFileOutput.getAbsolutePath() );
                FileUtils.copyDirectoryStructure( new File( javadocDir, docFile ), docFileOutput );
                List files =
                    FileUtils.getFileAndDirectoryNames( docFileOutput,
                                                        StringUtils.join( excludes.iterator(), "," ), null, true,
                                                        true, true, true );
                for ( Iterator it2 = files.iterator(); it2.hasNext(); )
                {
                    File file = new File( it2.next().toString() );

                    if ( file.isDirectory() )
                    {
                        FileUtils.deleteDirectory( file );
                    }
                    else
                    {
                        file.delete();
                    }
                }
            }
        }
    }

    /**
     * Method that gets the files or classes that would be included in the javadocs using the subpackages
     * parameter.
     *
     * @param sourceDirectory the directory where the source files are located
     * @param fileList        the list of all files found in the sourceDirectory
     * @param excludePackages package names to be excluded in the javadoc
     * @return a StringBuffer that contains the appended file names of the files to be included in the javadoc
     */
    protected static List getIncludedFiles( File sourceDirectory, String[] fileList, String[] excludePackages )
    {
        List files = new ArrayList();

        for ( int j = 0; j < fileList.length; j++ )
        {
            boolean include = true;
            for ( int k = 0; k < excludePackages.length && include; k++ )
            {
                // handle wildcards (*) in the excludePackageNames
                String[] excludeName = excludePackages[k].split( "[*]" );

                if ( excludeName.length == 0 )
                {
                    continue;
                }

                if ( excludeName.length > 1 )
                {
                    int u = 0;
                    while ( include && u < excludeName.length )
                    {
                        if ( !"".equals( excludeName[u].trim() ) && fileList[j].indexOf( excludeName[u] ) != -1 )
                        {
                            include = false;
                        }
                        u++;
                    }
                }
                else
                {
                    if ( fileList[j].startsWith( sourceDirectory.toString() + File.separatorChar + excludeName[0] ) )
                    {
                        if ( excludeName[0].endsWith( String.valueOf( File.separatorChar ) ) )
                        {
                            int i = fileList[j].lastIndexOf( File.separatorChar );
                            String packageName = fileList[j].substring( 0, i + 1 );
                            File currentPackage = new File( packageName );
                            File excludedPackage = new File( sourceDirectory, excludeName[0] );
                            if ( currentPackage.equals( excludedPackage )
                                && fileList[j].substring( i ).indexOf( ".java" ) != -1 )
                            {
                                include = true;
                            }
                            else
                            {
                                include = false;
                            }
                        }
                        else
                        {
                            include = false;
                        }
                    }
                }
            }

            if ( include )
            {
                files.add( quotedPathArgument( fileList[j] ) );
            }
        }

        return files;
    }

    /**
     * Method that gets the complete package names (including subpackages) of the packages that were defined
     * in the excludePackageNames parameter.
     *
     * @param sourceDirectory     the directory where the source files are located
     * @param excludePackagenames package names to be excluded in the javadoc
     * @return a List of the packagenames to be excluded
     */
    protected static List getExcludedPackages( String sourceDirectory, String[] excludePackagenames )
    {
        List files = new ArrayList();
        for ( int i = 0; i < excludePackagenames.length; i++ )
        {
            String[] fileList = FileUtils.getFilesFromExtension( sourceDirectory, new String[] { "java" } );
            for ( int j = 0; j < fileList.length; j++ )
            {
                String[] excludeName = excludePackagenames[i].split( "[*]" );
                int u = 0;
                while ( u < excludeName.length )
                {
                    if ( !"".equals( excludeName[u].trim() ) && fileList[j].indexOf( excludeName[u] ) != -1
                        && sourceDirectory.indexOf( excludeName[u] ) == -1 )
                    {
                        files.add( fileList[j] );
                    }
                    u++;
                }
            }
        }

        List excluded = new ArrayList();
        for ( Iterator it = files.iterator(); it.hasNext(); )
        {
            String file = (String) it.next();
            int idx = file.lastIndexOf( File.separatorChar );
            String tmpStr = file.substring( 0, idx );
            tmpStr = tmpStr.replace( '\\', '/' );
            String[] srcSplit = tmpStr.split( sourceDirectory.replace( '\\', '/' ) + '/' );
            String excludedPackage = srcSplit[1].replace( '/', '.' );

            if ( !excluded.contains( excludedPackage ) )
            {
                excluded.add( excludedPackage );
            }
        }

        return excluded;
    }

    /**
     * Convenience method that gets the files to be included in the javadoc.
     *
     * @param sourceDirectory the directory where the source files are located
     * @param files the variable that contains the appended filenames of the files to be included in the javadoc
     * @param excludePackages the packages to be excluded in the javadocs
     */
    protected static void addFilesFromSource( List files, File sourceDirectory, String[] excludePackages )
    {
        String[] fileList = FileUtils.getFilesFromExtension( sourceDirectory.getPath(), new String[] { "java" } );
        if ( fileList != null && fileList.length != 0 )
        {
            List tmpFiles = getIncludedFiles( sourceDirectory, fileList, excludePackages );
            files.addAll( tmpFiles );
        }
    }

    /**
     * Call the Javadoc tool and parse its output to find its version, i.e.:
     * <pre>
     * javadoc.exe(or .sh) -J-version
     * </pre>
     *
     * @param javadocExe not null file
     * @return the javadoc version as float
     * @throws IOException if javadocExe is null, doesn't exist or is not a file
     * @throws CommandLineException if any
     * @throws IllegalArgumentException if no output was found in the command line
     * @throws PatternSyntaxException if the output contains a syntax error in the regular-expression pattern.
     * @see #parseJavadocVersion(String)
     */
    protected static float getJavadocVersion( File javadocExe )
        throws IOException, CommandLineException, IllegalArgumentException, PatternSyntaxException
    {
        if ( ( javadocExe == null ) || ( !javadocExe.exists() ) || ( !javadocExe.isFile() ) )
        {
            throw new IOException( "The javadoc executable '" + javadocExe + "' doesn't exist or is not a file. " );
        }

        Commandline cmd = new Commandline();
        cmd.setExecutable( javadocExe.getAbsolutePath() );
        cmd.setWorkingDirectory( javadocExe.getParentFile() );
        cmd.createArg().setValue( "-J-version" );

        CommandLineUtils.StringStreamConsumer out = new CommandLineUtils.StringStreamConsumer();
        CommandLineUtils.StringStreamConsumer err = new CommandLineUtils.StringStreamConsumer();

        int exitCode = CommandLineUtils.executeCommandLine( cmd, out, err );

        if ( exitCode != 0 )
        {
            StringBuffer msg = new StringBuffer( "Exit code: " + exitCode + " - " + err.getOutput() );
            msg.append( '\n' );
            msg.append( "Command line was:" + CommandLineUtils.toString( cmd.getCommandline() ) );
            throw new CommandLineException( msg.toString() );
        }

        if ( StringUtils.isNotEmpty( err.getOutput() ) )
        {
            return parseJavadocVersion( err.getOutput() );
        }
        else if ( StringUtils.isNotEmpty( out.getOutput() ) )
        {
            return parseJavadocVersion( out.getOutput() );
        }

        throw new IllegalArgumentException( "No output found from the command line 'javadoc -J-version'" );
    }

    /**
     * Parse the output for 'javadoc -J-version' and return the javadoc version recognized.
     * <br/>
     * Here are some output for 'javadoc -J-version' depending the JDK used:
     * <table>
     * <tr>
     *   <th>JDK</th>
     *   <th>Output for 'javadoc -J-version'</th>
     * </tr>
     * <tr>
     *   <td>Sun 1.4</td>
     *   <td>java full version "1.4.2_12-b03"</td>
     * </tr>
     * <tr>
     *   <td>Sun 1.5</td>
     *   <td>java full version "1.5.0_07-164"</td>
     * </tr>
     * <tr>
     *   <td>IBM 1.4</td>
     *   <td>javadoc full version "J2RE 1.4.2 IBM Windows 32 build cn1420-20040626"</td>
     * </tr>
     * <tr>
     *   <td>IBM 1.5 (French JVM)</td>
     *   <td>javadoc version complète de "J2RE 1.5.0 IBM Windows 32 build pwi32pdev-20070426a"</td>
     * </tr>
     * <tr>
     *   <td>FreeBSD 1.5</td>
     *   <td>java full version "diablo-1.5.0-b01"</td>
     * </tr>
     * <tr>
     *   <td>BEA jrockit 1.5</td>
     *   <td>java full version "1.5.0_11-b03"</td>
     * </tr>
     * </table>
     *
     * @param output for 'javadoc -J-version'
     * @return the version of the javadoc for the output.
     * @throws PatternSyntaxException if the output doesn't match with the output pattern
     * <tt>(?s).*?([0-9]+\\.[0-9]+)(\\.([0-9]+))?.*</tt>.
     * @throws IllegalArgumentException if the output is null
     */
    protected static float parseJavadocVersion( String output )
        throws IllegalArgumentException, PatternSyntaxException
    {
        if ( StringUtils.isEmpty( output ) )
        {
            throw new IllegalArgumentException( "The output could not be null." );
        }

        Pattern pattern = Pattern.compile( "(?s).*?([0-9]+\\.[0-9]+)(\\.([0-9]+))?.*" );

        Matcher matcher = pattern.matcher( output );
        if ( !matcher.matches() )
        {
            throw new PatternSyntaxException( "Unrecognized version of Javadoc: '" + output + "'", pattern.pattern(),
                                              pattern.toString().length() - 1 );
        }

        String version = matcher.group( 3 );
        if ( version == null )
        {
            version = matcher.group( 1 );
        }
        else
        {
            version = matcher.group( 1 ) + version;
        }

        return Float.parseFloat( version );
    }

    /**
     * Parse a memory string which be used in the JVM arguments <code>-Xms</code> or <code>-Xmx</code>.
     * <br/>
     * Here are some supported memory string depending the JDK used:
     * <table>
     * <tr>
     *   <th>JDK</th>
     *   <th>Memory argument support for <code>-Xms</code> or <code>-Xmx</code></th>
     * </tr>
     * <tr>
     *   <td>SUN</td>
     *   <td>1024k | 128m | 1g | 1t</td>
     * </tr>
     * <tr>
     *   <td>IBM</td>
     *   <td>1024k | 1024b | 128m | 128mb | 1g | 1gb</td>
     * </tr>
     * <tr>
     *   <td>BEA</td>
     *   <td>1024k | 1024kb | 128m | 128mb | 1g | 1gb</td>
     * </tr>
     * </table>
     *
     * @param memory the memory to be parsed, not null.
     * @return the memory parsed with a supported unit. If no unit specified in the <code>memory</code> parameter,
     * the default unit is <code>m</code>. The units <code>g | gb</code> or <code>t | tb</code> will be converted
     * in <code>m</code>.
     * @throws IllegalArgumentException if the <code>memory</code> parameter is null or doesn't match any pattern.
     */
    protected static String parseJavadocMemory( String memory )
        throws IllegalArgumentException
    {
        if ( StringUtils.isEmpty( memory ) )
        {
            throw new IllegalArgumentException( "The memory could not be null." );
        }

        Pattern p = Pattern.compile( "^\\s*(\\d+)\\s*?\\s*$" );
        Matcher m = p.matcher( memory );
        if ( m.matches() )
        {
            return m.group( 1 ) + "m";
        }

        p = Pattern.compile( "^\\s*(\\d+)\\s*k(b)?\\s*$", Pattern.CASE_INSENSITIVE );
        m = p.matcher( memory );
        if ( m.matches() )
        {
            return m.group( 1 ) + "k";
        }

        p = Pattern.compile( "^\\s*(\\d+)\\s*m(b)?\\s*$", Pattern.CASE_INSENSITIVE );
        m = p.matcher( memory );
        if ( m.matches() )
        {
            return m.group( 1 ) + "m";
        }

        p = Pattern.compile( "^\\s*(\\d+)\\s*g(b)?\\s*$", Pattern.CASE_INSENSITIVE );
        m = p.matcher( memory );
        if ( m.matches() )
        {
            return ( Integer.parseInt( m.group( 1 ) ) * 1024 ) + "m";
        }

        p = Pattern.compile( "^\\s*(\\d+)\\s*t(b)?\\s*$", Pattern.CASE_INSENSITIVE );
        m = p.matcher( memory );
        if ( m.matches() )
        {
            return ( Integer.parseInt( m.group( 1 ) ) * 1024 * 1024 ) + "m";
        }

        throw new IllegalArgumentException( "Could convert not to a memory size: " + memory );
    }

    /**
     * Fetch an URL
     *
     * @param settings the user settings used to fetch the url with an active proxy, if defined.
     * @param url the url to fetch
     * @throws IOException if any
     */
    protected static void fetchURL( Settings settings, URL url )
        throws IOException
    {
        if ( url == null )
        {
            throw new IllegalArgumentException( "The url is null" );
        }

        HttpClient httpClient = null;
        if ( !"file".equals( url.getProtocol() ) )
        {
            httpClient = new HttpClient();
            httpClient.getHttpConnectionManager().getParams().setConnectionTimeout( 1000 );

            if ( settings != null )
            {
                Proxy activeProxy = settings.getActiveProxy();

                if ( activeProxy != null )
                {
                    String proxyHost = settings.getActiveProxy().getHost();
                    int proxyPort = settings.getActiveProxy().getPort();

                    String proxyUser = settings.getActiveProxy().getUsername();
                    String proxyPass = settings.getActiveProxy().getPassword();

                    if ( StringUtils.isNotEmpty( proxyHost ) )
                    {
                        httpClient.getHostConfiguration().setProxy( proxyHost, proxyPort );
                    }

                    if ( StringUtils.isNotEmpty( proxyUser ) )
                    {
                        AuthScope authScope =
                            new AuthScope( AuthScope.ANY_HOST, AuthScope.ANY_PORT, AuthScope.ANY_REALM,
                                           AuthScope.ANY_SCHEME );
                        UsernamePasswordCredentials usernamePasswordCredentials =
                            new UsernamePasswordCredentials( proxyUser, proxyPass );
                        httpClient.getState().setProxyCredentials( authScope, usernamePasswordCredentials );
                    }
                }
            }
        }

        InputStream in = null;
        try
        {
            if ( httpClient != null )
            {
                GetMethod getMethod = new GetMethod( url.toString() );

                try
                {
                    int status = httpClient.executeMethod( getMethod );
                    if ( status != 200 )
                    {
                        throw new FileNotFoundException( url.toString() );
                    }
                }
                finally
                {
                    getMethod.releaseConnection();
                }
            }
            else
            {
                in = url.openStream();
            }
        }
        finally
        {
            IOUtil.close( in );
        }
    }

    /**
     * Validate if a charset is supported on this platform.
     *
     * @param charsetName the charsetName to be check.
     * @return <code>true</code> if the charset is supported by the JVM, <code>false</code> otherwise.
     */
    protected static boolean validateEncoding( String charsetName )
    {
        if ( StringUtils.isEmpty( charsetName ) )
        {
            return false;
        }

        OutputStream ost = new ByteArrayOutputStream();
        OutputStreamWriter osw = null;
        try
        {
            osw = new OutputStreamWriter( ost, charsetName );
        }
        catch ( UnsupportedEncodingException exc )
        {
            return false;
        }
        finally
        {
            IOUtil.close( osw );
        }
        return true;
    }

    /**
     * For security reasons, if an active proxy is defined and needs an authentication by
     * username/password, hide the proxy password in the command line.
     *
     * @param cmdLine a command line, not null
     * @param settings the user settings
     * @return the cmdline with '*' for the http.proxyPassword JVM property
     */
    protected static String hideProxyPassword( String cmdLine, Settings settings )
    {
        if ( cmdLine == null )
        {
            throw new IllegalArgumentException( "cmdLine could not be null" );
        }

        if ( settings == null )
        {
            return cmdLine;
        }

        Proxy activeProxy = settings.getActiveProxy();
        if ( activeProxy != null && StringUtils.isNotEmpty( activeProxy.getHost() )
            && StringUtils.isNotEmpty( activeProxy.getUsername() )
            && StringUtils.isNotEmpty( activeProxy.getPassword() ) )
        {
            String pass = "-J-Dhttp.proxyPassword=\"" + activeProxy.getPassword() + "\"";
            String hidepass =
                "-J-Dhttp.proxyPassword=\"" + StringUtils.repeat( "*", activeProxy.getPassword().length() ) + "\"";

            return StringUtils.replace( cmdLine, pass, hidepass );
        }

        return cmdLine;
    }

    /**
     * Auto-detect the class names of the implementation of <code>com.sun.tools.doclets.Taglet</code> class from a
     * given jar file.
     * <br/>
     * <b>Note</b>: <code>JAVA_HOME/lib/tools.jar</code> is a requirement to find
     * <code>com.sun.tools.doclets.Taglet</code> class.
     *
     * @param jarFile not null
     * @return the list of <code>com.sun.tools.doclets.Taglet</code> class names from a given jarFile.
     * @throws IOException if jarFile is invalid or not found, or if the <code>JAVA_HOME/lib/tools.jar</code>
     * is not found.
     * @throws ClassNotFoundException if any
     * @throws NoClassDefFoundError if any
     */
    protected static List getTagletClassNames( File jarFile )
        throws IOException, ClassNotFoundException, NoClassDefFoundError
    {
        List classes = getClassNamesFromJar( jarFile );
        ClassLoader cl;

        // Needed to find com.sun.tools.doclets.Taglet class
        File tools = new File( System.getProperty( "java.home" ), "../lib/tools.jar" );
        if ( tools.exists() && tools.isFile() )
        {
            cl = new URLClassLoader( new URL[] { jarFile.toURI().toURL(), tools.toURI().toURL() }, null );
        }
        else
        {
            cl = new URLClassLoader( new URL[] { jarFile.toURI().toURL() }, null );
        }

        List tagletClasses = new ArrayList();

        Class tagletClass = cl.loadClass( "com.sun.tools.doclets.Taglet" );
        for ( Iterator it = classes.iterator(); it.hasNext(); )
        {
            String s = (String) it.next();

            Class c = cl.loadClass( s );

            if ( tagletClass.isAssignableFrom( c ) && !Modifier.isAbstract( c.getModifiers() ) )
            {
                tagletClasses.add( c.getName() );
            }
        }

        return tagletClasses;
    }

    /**
     * Copy the given url to the given file.
     *
     * @param url not null url
     * @param file not null file where the url will be created
     * @throws IOException if any
     * @since 2.6
     */
    protected static void copyResource( URL url, File file )
        throws IOException
    {
        if ( file == null )
        {
            throw new IOException( "The file " + file + " can't be null." );
        }
        if ( url == null )
        {
            throw new IOException( "The url " + url + " could not be null." );
        }

        InputStream is = url.openStream();
        if ( is == null )
        {
            throw new IOException( "The resource " + url + " doesn't exists." );
        }

        if ( !file.getParentFile().exists() )
        {
            file.getParentFile().mkdirs();
        }

        FileOutputStream os = null;
        try
        {
            os = new FileOutputStream( file );

            IOUtil.copy( is, os );
        }
        finally
        {
            IOUtil.close( is );

            IOUtil.close( os );
        }
    }

    /**
     * Invoke Maven for the given project file with a list of goals and properties, the output will be in the
     * invokerlog file.
     * <br/>
     * <b>Note</b>: the Maven Home should be defined in the <code>maven.home</code> Java system property or defined in
     * <code>M2_HOME</code> system env variables.
     *
     * @param log a logger could be null.
     * @param project a not null project file.
     * @param goals a not null goals list.
     * @param properties the properties for the goals, could be null.
     * @param invokerLog the log file where the invoker will be written, if null using <code>System.out</code>.
     * @since 2.6
     */
    protected static void invokeMaven( Log log, File projectFile, List goals, Properties properties,
                                       File invokerLog )
    {
        if ( projectFile == null )
        {
            throw new IllegalArgumentException( "projectFile should be not null." );
        }
        if ( !projectFile.isFile() )
        {
            throw new IllegalArgumentException( projectFile.getAbsolutePath() + " is not a file." );
        }
        if ( goals == null || goals.size() == 0 )
        {
            throw new IllegalArgumentException( "goals should be not empty." );
        }

        String mavenHome = getMavenHome( log );
        if ( StringUtils.isEmpty( mavenHome ) )
        {
            String msg =
                "Could NOT invoke Maven because no Maven Home is defined. You need to have set the M2_HOME "
                    + "system env variable or a maven.home Java system properties.";
            if ( log != null )
            {
                log.error( msg );
            }
            else
            {
                System.err.println( msg );
            }
            return;
        }

        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome( new File( mavenHome ) );

        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory( projectFile.getParentFile() );
        request.setPomFile( projectFile );

        if ( log != null )
        {
            request.setDebug( log.isDebugEnabled() );
        }
        else
        {
            request.setDebug( true );
        }
        request.setGoals( goals );
        if ( properties != null )
        {
            request.setProperties( properties );
        }

        InvocationResult result;
        try
        {
            if ( log != null )
            {
                log.debug( "Invoking Maven for the goals: " + goals + " with properties=" + properties );
            }
            result = invoke( log, invoker, request, invokerLog, goals, properties, null );
        }
        catch ( MavenInvocationException e )
        {
            if ( log != null )
            {
                if ( log.isDebugEnabled() )
                {
                    log.error( "MavenInvocationException: " + e.getMessage(), e );
                }
                else
                {
                    log.error( "MavenInvocationException: " + e.getMessage() );
                }
                log.error( "Error when invoking Maven, consult the invoker log." );
            }
            return;
        }

        String invokerLogContent = null;
        Reader reader = null;
        try
        {
            reader = ReaderFactory.newReader( invokerLog, "UTF-8" );
            invokerLogContent = IOUtil.toString( reader );
        }
        catch ( IOException e )
        {
            if ( log != null )
            {
                log.error( "IOException: " + e.getMessage() );
            }
        }
        finally
        {
            IOUtil.close( reader );
        }

        if ( invokerLogContent != null
            && invokerLogContent.indexOf( "Error occurred during initialization of VM" ) != -1 )
        {
            if ( log != null )
            {
                log.info( "Error occurred during initialization of VM, try to use an empty MAVEN_OPTS." );

                log.debug( "Reinvoking Maven for the goals: " + goals + " with an empty MAVEN_OPTS" );
            }
            try
            {
                result = invoke( log, invoker, request, invokerLog, goals, properties, "" );
            }
            catch ( MavenInvocationException e )
            {
                if ( log != null )
                {
                    if ( log.isDebugEnabled() )
                    {
                        log.error( "MavenInvocationException: " + e.getMessage(), e );
                    }
                    else
                    {
                        log.error( "MavenInvocationException: " + e.getMessage() );
                    }
                    log.error( "Error when reinvoking Maven, consult the invoker log." );
                }
                return;
            }
        }

        if ( result.getExitCode() != 0 )
        {
            if ( log != null )
            {
                log.error( "Error when invoking Maven, consult the invoker log file: "
                    + invokerLog.getAbsolutePath() );
            }
        }
    }

    // ----------------------------------------------------------------------
    // private methods
    // ----------------------------------------------------------------------

    /**
     * @param jarFile not null
     * @return all class names from the given jar file.
     * @throws IOException if any or if the jarFile is null or doesn't exist.
     */
    private static List getClassNamesFromJar( File jarFile )
        throws IOException
    {
        if ( jarFile == null || !jarFile.exists() || !jarFile.isFile() )
        {
            throw new IOException( "The jar '" + jarFile + "' doesn't exist or is not a file." );
        }

        List classes = new ArrayList();
        JarInputStream jarStream = null;

        try
        {
            jarStream = new JarInputStream( new FileInputStream( jarFile ) );
            JarEntry jarEntry = jarStream.getNextJarEntry();
            while ( jarEntry != null )
            {
                if ( jarEntry == null )
                {
                    break;
                }

                if ( jarEntry.getName().toLowerCase( Locale.ENGLISH ).endsWith( ".class" ) )
                {
                    String name = jarEntry.getName().substring( 0, jarEntry.getName().indexOf( "." ) );

                    classes.add( name.replaceAll( "/", "\\." ) );
                }

                jarStream.closeEntry();
                jarEntry = jarStream.getNextJarEntry();
            }
        }
        finally
        {
            IOUtil.close( jarStream );
        }

        return classes;
    }

    /**
     * @param log could be null
     * @param invoker not null
     * @param request not null
     * @param invokerLog not null
     * @param goals not null
     * @param properties could be null
     * @param mavenOpts could be null
     * @return the invocation result
     * @throws MavenInvocationException if any
     * @since 2.6
     */
    private static InvocationResult invoke( Log log, Invoker invoker, InvocationRequest request, File invokerLog,
                                            List goals, Properties properties, String mavenOpts )
        throws MavenInvocationException
    {
        PrintStream ps;
        OutputStream os = null;
        if ( invokerLog != null )
        {
            log.debug( "Using "+ invokerLog.getAbsolutePath() + " to log the invoker" );

            try
            {
                if ( !invokerLog.exists() )
                {
                    invokerLog.getParentFile().mkdirs();
                }
                os = new FileOutputStream( invokerLog );
                ps = new PrintStream( os, true, "UTF-8" );
            }
            catch ( FileNotFoundException e )
            {
                if ( log != null )
                {
                    log.error( "FileNotFoundException: " + e.getMessage() + ". Using System.out to log the invoker." );
                }
                ps = System.out;
            }
            catch ( UnsupportedEncodingException e )
            {
                if ( log != null )
                {
                    log.error( "UnsupportedEncodingException: " + e.getMessage()
                        + ". Using System.out to log the invoker." );
                }
                ps = System.out;
            }
        }
        else
        {
            log.debug( "Using System.out to log the invoker." );

            ps = System.out;
        }

        if ( mavenOpts != null )
        {
            request.setMavenOpts( mavenOpts );
        }

        InvocationOutputHandler outputHandler = new PrintStreamHandler( ps, false );
        request.setOutputHandler( outputHandler );

        outputHandler.consumeLine( "Invoking Maven for the goals: " + goals + " with properties=" + properties );
        outputHandler.consumeLine( "" );
        outputHandler.consumeLine( "M2_HOME=" + getMavenHome( log ) );
        outputHandler.consumeLine( "MAVEN_OPTS=" + getMavenOpts( log ) );
        outputHandler.consumeLine( "JAVA_HOME=" + getJavaHome( log ) );
        outputHandler.consumeLine( "JAVA_OPTS=" + getJavaOpts( log ) );
        outputHandler.consumeLine( "" );

        try
        {
            return invoker.execute( request );
        }
        finally
        {
            IOUtil.close( os );
            ps = null;
        }
    }

    /**
     * @param log a logger could be null
     * @return the Maven home defined in the <code>maven.home</code> system property or defined
     * in <code>M2_HOME</code> system env variables or null if never setted.
     * @since 2.6
     */
    private static String getMavenHome( Log log )
    {
        String mavenHome = System.getProperty( "maven.home" );
        if ( mavenHome == null )
        {
            try
            {
                mavenHome = CommandLineUtils.getSystemEnvVars().getProperty( "M2_HOME" );
            }
            catch ( IOException e )
            {
                if ( log != null )
                {
                    log.debug( "IOException: " + e.getMessage() );
                }
            }
        }

        File m2Home = new File( mavenHome );
        if ( !m2Home.exists() )
        {
            if ( log != null )
            {
                log
                   .error( "Cannot find Maven application directory. Either specify \'maven.home\' system property, or "
                       + "M2_HOME environment variable." );
            }
        }

        return mavenHome;
    }

    /**
     * @param log a logger could be null
     * @return the <code>MAVEN_OPTS</code> env variable value
     * @since 2.6
     */
    private static String getMavenOpts( Log log )
    {
        String mavenOpts = null;
        try
        {
            mavenOpts = CommandLineUtils.getSystemEnvVars().getProperty( "MAVEN_OPTS" );
        }
        catch ( IOException e )
        {
            if ( log != null )
            {
                log.debug( "IOException: " + e.getMessage() );
            }
        }

        return mavenOpts;
    }

    /**
     * @param log a logger could be null
     * @return the <code>JAVA_HOME</code> from System.getProperty( "java.home" )
     * By default, <code>System.getProperty( "java.home" ) = JRE_HOME</code> and <code>JRE_HOME</code>
     * should be in the <code>JDK_HOME</code>
     * @since 2.6
     */
    private static File getJavaHome( Log log )
    {
        File javaHome;
        if ( SystemUtils.IS_OS_MAC_OSX )
        {
            javaHome = SystemUtils.getJavaHome();
        }
        else
        {
            javaHome = new File( SystemUtils.getJavaHome(), ".." );
        }

        if ( javaHome == null || !javaHome.exists() )
        {
            try
            {
                javaHome = new File( CommandLineUtils.getSystemEnvVars().getProperty( "JAVA_HOME" ) );
            }
            catch ( IOException e )
            {
                if ( log != null )
                {
                    log.debug( "IOException: " + e.getMessage() );
                }
            }
        }

        if ( javaHome == null || !javaHome.exists() )
        {
            if ( log != null )
            {
                log.error( "Cannot find Java application directory. Either specify \'java.home\' system property, or "
                    + "JAVA_HOME environment variable." );
            }
        }

        return javaHome;
    }

    /**
     * @param log a logger could be null
     * @return the <code>JAVA_OPTS</code> env variable value
     * @since 2.6
     */
    private static String getJavaOpts( Log log )
    {
        String javaOpts= null;
        try
        {
            javaOpts = CommandLineUtils.getSystemEnvVars().getProperty( "JAVA_OPTS" );
        }
        catch ( IOException e )
        {
            if ( log != null )
            {
                log.debug( "IOException: " + e.getMessage() );
            }
        }

        return javaOpts;
    }
}