package org.apache.maven.plugin.war.packaging;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.war.util.PathSet;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.IOException;

/**
 * Handles the project own resources, that is:
 * <ul
 * <li>The list of web resources, if any</li>
 * <li>The content of the webapp directory if it exists</li>
 * <li>The custom deployment descriptor(s), if any</li>
 * <li>The content of the classes directory if it exists</li>
 * <li>The dependencies of the project</li>
 * </ul>
 *
 * @author Stephane Nicoll
 */
public class WarProjectPackagingTask
    extends AbstractWarPackagingTask
{
    private final Resource[] webResources;

    private final File webXml;

    private final File containerConfigXML;


    public WarProjectPackagingTask( Resource[] webResources, File webXml, File containerConfigXml )
    {
        if ( webResources != null )
        {
            this.webResources = webResources;
        }
        else
        {
            this.webResources = new Resource[0];
        }
        this.webXml = webXml;
        this.containerConfigXML = containerConfigXml;
    }

    public void performPackaging( WarPackagingContext context )
        throws MojoExecutionException, MojoFailureException
    {

        // Prepare the INF directories
        File webinfDir = new File( context.getWebappDirectory(), WEB_INF_PATH );
        webinfDir.mkdirs();
        File metainfDir = new File( context.getWebappDirectory(), META_INF_PATH );
        metainfDir.mkdirs();

        handleWebResources( context );

        handeWebAppSourceDirectory( context );

        handleDeploymentDescriptors( context, webinfDir, metainfDir );

        handleClassesDirectory( context );

        handleArtifacts( context );
    }


    /**
     * Handles the web resources.
     *
     * @param context the packaging context
     * @throws MojoExecutionException if a resource could not be copied
     */
    protected void handleWebResources( WarPackagingContext context )
        throws MojoExecutionException
    {
        for ( int i = 0; i < webResources.length; i++ )
        {
            Resource resource = webResources[i];
            if ( !( new File( resource.getDirectory() ) ).isAbsolute() )
            {
                resource.setDirectory( context.getProject().getBasedir() + File.separator + resource.getDirectory() );
            }

            // Make sure that the resource directory is not the same as the webappDirectory
            if ( !resource.getDirectory().equals( context.getWebappDirectory().getPath() ) )
            {

                try
                {
                    copyResources( context, resource );
                }
                catch ( IOException e )
                {
                    throw new MojoExecutionException( "Could not copy resource[" + resource.getDirectory() + "]", e );
                }
            }
        }
    }

    /**
     * Handles the webapp sources.
     *
     * @param context the packaging context
     * @throws MojoExecutionException if the sources could not be copied
     */
    protected void handeWebAppSourceDirectory( WarPackagingContext context )
        throws MojoExecutionException
    {
        if ( !context.getWebappSourceDirectory().exists() )
        {
            context.getLog().debug( "webapp sources directory does not exist - skipping." );
        }
        else
        if ( !context.getWebappSourceDirectory().getAbsolutePath().equals( context.getWebappDirectory().getPath() ) )
        {
            final PathSet sources = getFilesToIncludes( context.getWebappSourceDirectory(),
                                                        context.getWebappSourceIncludes(),
                                                        context.getWebappSourceExcludes() );

            try
            {
                copyFiles( context, context.getWebappSourceDirectory(), sources );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException(
                    "Could not copy webapp sources[" + context.getWebappDirectory().getAbsolutePath() + "]", e );
            }
        }
    }

    /**
     * Handles the webapp artifacts.
     *
     * @param context the packaging context
     * @throws MojoExecutionException if the artifacts could not be packaged
     */
    protected void handleArtifacts( WarPackagingContext context )
        throws MojoExecutionException
    {
        ArtifactsPackagingTask task = new ArtifactsPackagingTask( context.getProject().getArtifacts() );
        task.performPackaging( context );
    }

    /**
     * Handles the webapp classes.
     *
     * @param context the packaging context
     * @throws MojoExecutionException if the classes could not be packaged
     */
    protected void handleClassesDirectory( WarPackagingContext context )
        throws MojoExecutionException
    {
        ClassesPackagingTask task = new ClassesPackagingTask();
        task.performPackaging( context );
    }

    /**
     * Handles the deployment descriptors, if specified. Note that the behavior
     * here is slightly different since the customized entry always win, even if
     * an overlay has already packaged a web.xml previously.
     *
     * @param context    the packaging context
     * @param webinfDir  the web-inf directory
     * @param metainfDir the meta-inf directory
     * @throws MojoFailureException   if the web.xml is specified but does not exist
     * @throws MojoExecutionException if an error occured while copying the descriptors
     */
    protected void handleDeploymentDescriptors( WarPackagingContext context, File webinfDir, File metainfDir )
        throws MojoFailureException, MojoExecutionException
    {
        try
        {
            if ( webXml != null && StringUtils.isNotEmpty( webXml.getName() ) )
            {
                if ( !webXml.exists() )
                {
                    throw new MojoFailureException( "The specified web.xml file '" + webXml + "' does not exist" );
                }

                //rename to web.xml
                //TODO refactor this
                copyFileIfModified( webXml, new File( webinfDir, "web.xml" ) );
                context.getProtectedFiles().add( WEB_INF_PATH + "/web.xml" );
            }

            if ( containerConfigXML != null && StringUtils.isNotEmpty( containerConfigXML.getName() ) )
            {
                String xmlFileName = containerConfigXML.getName();
                copyFileIfModified( containerConfigXML, new File( metainfDir, xmlFileName ) );
                context.getProtectedFiles().add( META_INF_PATH + "/" + xmlFileName );
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to copy deployment descriptor", e );
        }
    }


    /**
     * Copies webapp webResources from the specified directory.
     *
     * @param context  the war packaging context to use
     * @param resource the resource to copy
     * @throws IOException            if an error occured while copying the resources
     * @throws MojoExecutionException if an error occured while retrieving the filter properties
     */
    public void copyResources( WarPackagingContext context, Resource resource )
        throws IOException, MojoExecutionException
    {
        if ( !context.getWebappDirectory().exists() )
        {
            context.getLog().warn( "Not copying webapp webResources[" + resource.getDirectory() +
                "]: webapp directory[" + context.getWebappDirectory().getAbsolutePath() + "] does not exist!" );
        }

        context.getLog().info( "Copy webapp webResources[" + resource.getDirectory() + "] to[" +
            context.getWebappDirectory().getAbsolutePath() + "]" );
        String[] fileNames = getFilesToCopy( resource );
        for ( int i = 0; i < fileNames.length; i++ )
        {
            String targetFileName = fileNames[i];
            if ( resource.getTargetPath() != null )
            {
                //TODO make sure this thing is 100% safe
                targetFileName = resource.getTargetPath() + File.separator + targetFileName;
            }
            if ( resource.isFiltering() )
            {
                copyFilteredFile( context, new File( resource.getDirectory(), fileNames[i] ), targetFileName );
            }
            else
            {
                copyFile( context, new File( resource.getDirectory(), fileNames[i] ), targetFileName );
            }
        }
    }


    /**
     * Returns a list of filenames that should be copied
     * over to the destination directory.
     *
     * @param resource the resource to be scanned
     * @return the array of filenames, relative to the sourceDir
     */
    private String[] getFilesToCopy( Resource resource )
    {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir( resource.getDirectory() );
        if ( resource.getIncludes() != null && !resource.getIncludes().isEmpty() )
        {
            scanner.setIncludes(
                (String[]) resource.getIncludes().toArray( new String[resource.getIncludes().size()] ) );
        }
        else
        {
            scanner.setIncludes( DEFAULT_INCLUDES );
        }
        if ( resource.getExcludes() != null && !resource.getExcludes().isEmpty() )
        {
            scanner.setExcludes(
                (String[]) resource.getExcludes().toArray( new String[resource.getExcludes().size()] ) );
        }

        scanner.addDefaultExcludes();

        scanner.scan();

        return scanner.getIncludedFiles();
    }
}
