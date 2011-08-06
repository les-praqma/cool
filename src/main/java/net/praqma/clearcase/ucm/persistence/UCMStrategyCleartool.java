package net.praqma.clearcase.ucm.persistence;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.praqma.clearcase.Cool;
import net.praqma.clearcase.Region;
import net.praqma.clearcase.Site;
import net.praqma.clearcase.PVob;
import net.praqma.clearcase.Vob;
import net.praqma.clearcase.cleartool.Cleartool;
import net.praqma.clearcase.ucm.UCMException;
import net.praqma.clearcase.ucm.UCMException.UCMType;

import net.praqma.clearcase.ucm.entities.Component;
import net.praqma.clearcase.ucm.entities.Project;
import net.praqma.clearcase.ucm.entities.Stream;
import net.praqma.clearcase.ucm.entities.UCM;
import net.praqma.clearcase.ucm.entities.UCMEntity;
import net.praqma.clearcase.ucm.view.SnapshotView;
import net.praqma.clearcase.ucm.view.UCMView;
import net.praqma.util.execute.AbnormalProcessTerminationException;
import net.praqma.util.execute.CmdResult;
import net.praqma.util.io.IO;
import net.praqma.util.structure.Tuple;
import sun.misc.Regexp;

public class UCMStrategyCleartool extends Cool implements UCMStrategyInterface {
	private static final String rx_ccdef_allowed = "[\\w\\.-_\\\\]";

	/* Some relatively hard coded "variables" */
	public static final String __TAG_NAME = "tag";

	public static final String tag_install_description = "Installation: \"cleartool mkhltype "
			+ __TAG_NAME + " -c \"Hyperlink type for tagging entities\"\"";

	static {
		// logger.ExcludeClass( UCMStrategyXML.class.getName() );
	}

	private static final String filesep = System.getProperty("file.separator");

	public UCMStrategyCleartool() {
		logger.log("Using ClearTool strategy");
	}

	/**/
	public String getMastership(String fqname) throws UCMException {
		String cmd = "describe -fmt %[master]p " + fqname;

		CmdResult ms = null;

		try {
			ms = Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("The mastership was undefined. ");
		}

		return ms.stdoutBuffer.toString();
	}

	/************************************************************************
	 * PROJECT FUNCTIONALITY
	 ************************************************************************/

	public String getProjectFromStream( String stream )
	{
		String cmd = "desc -fmt %[project]p " + stream;
		return Cleartool.run(cmd).stdoutBuffer.toString().trim();
	}

	public List<String> getModifiableComponents( String project )
	{
		String cmd = "desc -fmt %[mod_comps]p " + project;
		return Arrays.asList(Cleartool.run(cmd).stdoutBuffer.toString().split(
				"\\s+"));
	}


	public String loadProject( String project ) throws UCMException
	{
		logger.debug( project );

		String cmd = "lsproj -fmt %[istream]Xp " + project;

		try {
			return Cleartool.run(cmd).stdoutBuffer.toString();
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException(e.getMessage(), e.getMessage());
		}
	}
	
	public void createProject( String name, String root, PVob pvob, int policy, String comment, Component ... mcomps ) throws UCMException {
		String cmd = "mkproject" +  ( comment != null ? " -c \"" + comment + "\"" : "" ) + " -in " + ( root==null ? "RootFolder" : root ) + " -modcomp ";
		for( Component c : mcomps) {
			cmd += c.getFullyQualifiedName() + " ";
		}
		if( policy > 0 ) {
			cmd += " -policy " + Project.getPolicy( policy );
		}
		cmd += " " + name + "@" + pvob;
		
		try {
			Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException( "Could not create Project " + root + ": " + e.getMessage(), UCMType.CREATION_FAILED);
		}
	}
	
	/* or /f %i in ('cleartool lsproject -s -invob \Cool_PVOB') do @cleartool desc -fmt "project:%i@\Cool_PVOB %[istream]Xp\n" project:%i@\Cool_PVOB */
	
	public List<Project> getProjects( PVob vob ) throws UCMException {
		String cmd = "lsproject -s -invob " + vob.toString();
		
		List<String> projs = null;
		
		try {
			projs = Cleartool.run(cmd).stdoutList;
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException(e.getMessage(), e.getMessage());
		}
		
		List<Project> projects = new ArrayList<Project>();
		for( String p : projs ) {
			projects.add(UCMEntity.getProject(p + "@" + vob));
		}
		
		return projects;
	}

	/************************************************************************
	 * ACTIVITY FUNCTIONALITY
	 *
	 * @throws UCMException
	 ************************************************************************/


	public String loadActivity( String activity ) throws UCMException
	{
		String cmd = "describe -fmt %u " + activity;
		try {
			return Cleartool.run(cmd).stdoutBuffer.toString();
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException(e.getMessage(), e.getMessage());
		}
	}

	/************************************************************************
	 * BASELINE FUNCTIONALITY
	 *
	 * @throws UCMException
	 ************************************************************************/

	public String loadBaseline( String baseline ) throws UCMException
	{
		logger.debug( "Loading " + baseline );

		String cmd = "desc -fmt %n" + Cool.delim + "%[component]p" + Cool.delim + "%[bl_stream]p" + Cool.delim + "%[plevel]p" + Cool.delim + "%u " + baseline;
		try
		{
			return Cleartool.run( cmd ).stdoutBuffer.toString();
		}
		catch( AbnormalProcessTerminationException e )
		{
			throw new UCMException( "Could not load the baseline " + baseline, e.getMessage() );
		}
	}


	public List<String> getBaselineDiff( File dir, String baseline, String other, boolean nmerge, String pvob ) throws UCMException
	{
		/* Check if we are in view context */
		checkViewContext( dir );

		String cmd = "diffbl -pre -act -ver -nmerge " + baseline;

		try {
			return Cleartool.run(cmd, dir).stdoutList;
		} catch (AbnormalProcessTerminationException e) {
			if (e.getMessage()
					.equalsIgnoreCase(
							"cleartool: Error: The -nmerge option requires that both baselines be from the same stream.")) {
				logger.log("The given Baseline, \"" + baseline
						+ "\" is the first on the Stream");

				List<String> result = new ArrayList<String>();

				result.add(">> no_activity@" + pvob + " \"NO ACTIVITY\"");

				List<String> vobs = ListVobs(dir);

				for (String vob : vobs) {
					List<String> files = Cleartool
							.run("ls -s -rec " + vob, dir).stdoutList;

					/* Remove lost + found folder */
					for (int i = 0; i < files.size(); i++) {
						if (!files.get(i).matches("^lost+found@@.*")) {
							result.add(dir + filesep + files.get(i));
						}
					}
				}

				return result;
			}

			/* The exception could not be handled! */
			throw new UCMException("Could not get the baseline differences",
					e.getMessage());
		}
	}

	public void createBaseline(String fqname, Component component, File view, boolean incremental, boolean identical, Component[] depends) throws UCMException {
		String cmd = "mkbl -component " + component.getFullyQualifiedName() 
				                        + (identical ? " -identical" : "")
                                        + (incremental ? " -incremental" : " -full");
		
		if( depends != null ) {
			cmd += " -adepends_on";
			for( Component c : depends ) {
				cmd += " " + c.getFullyQualifiedName() + ", ";
			}
			cmd = cmd.substring( 0, ( cmd.length() - 2 ) );
		}
		
		cmd += " " + fqname;
		
		try {
			if(view != null) {
				Cleartool.run(cmd, view);
			} else {
				Cleartool.run(cmd);
			}
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not create Baseline " + fqname,
					e.getMessage());
		}
	}

	@Override

	public void setPromotionLevel( String baseline, String plevel ) throws UCMException
	{
		String cmd = "chbl -level " + plevel + " " + baseline;
		try {
			Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not set promotion level to " + plevel
					+ " for " + baseline + ": " + e.getMessage(),
					e.getMessage());
		}
	}

	@Override

	public String getBaselineActivities( String baseline )
	{
		// TODO Auto-generated method stub
		return null;
	}

	public String deliver(String baseline, String stream, String target,
			File context, String viewtag, boolean force, boolean complete,
			boolean abort) throws UCMException {
		String cmd = "deliver" + (force ? " -force" : "")
				+ (complete ? " -complete" : "") + (abort ? " -abort" : "");
		cmd += (baseline != null ? " -baseline " + baseline : "");
		cmd += (stream != null ? " -stream " + stream : "");
		cmd += (target != null ? " -target " + target : "");
		cmd += (viewtag != null ? " -to " + viewtag : "");

		try {
			String result = Cleartool.run(cmd, context, true).stdoutBuffer
					.toString();
			return result;
		} catch (AbnormalProcessTerminationException e) {
			logger.warning("Could not deliver to target " + target);
			logger.warning(e);
			throw new UCMException("Could not deliver: " + e.getMessage(),
					e.getMessage());
		}
	}

	public void cancelDeliver(File viewcontext) throws UCMException {
		try {
			String cmd = "deliver -cancel";
			Cleartool.run(cmd, viewcontext);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not cancel deliver: "
					+ e.getMessage(), e.getMessage());
		}
	}

	public String deliverStatus(String stream) throws UCMException {
		try {
			String cmd = "deliver -status -stream " + stream;
			return Cleartool.run(cmd).stdoutBuffer.toString();
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not get deliver status: "
					+ e.getMessage(), e.getMessage());
		}
	}

	/************************************************************************
	 * COMPONENT FUNCTIONALITY
	 ************************************************************************/

	private static final String rx_component_load = "\\s*Error: component not found\\s*";

	@Override
	public List<String> getBaselines( String component, String stream, String plevel )
	{
		String cmd = "lsbl -s -component " + component + " -stream " + stream + " -level " + plevel;
		return Cleartool.run( cmd ).stdoutList;
	}

	@Override
	public String getRootDir( String component )
	{
		logger.debug( component );

		String cmd = "desc -fmt %[root_dir]p " + component;
		return Cleartool.run(cmd).stdoutBuffer.toString();
	}
	public String loadComponent( String component ) throws UCMException
	{
		String cmd = "describe -fmt %[name]p " + component;
		try {
			Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			if (e.getMessage().matches(rx_component_load)) {
				throw new UCMException("The component \"" + component
						+ "\", does not exist.", UCMType.LOAD_FAILED);
			} else {
				throw new UCMException(e.getMessage(), e.getMessage(),
						UCMType.LOAD_FAILED);
			}
		}

		return "";
	}
	
	public void createComponent( String name, PVob pvob, String root, String comment ) throws UCMException {
		String cmd = "mkcomp" + ( comment != null ? " -c \"" + comment + "\"" : "" ) + ( root != null ? " -root " + root : " -nroot") + " " + name + "@" + pvob;
		
		try {
			Cleartool.run(cmd);
		} catch( Exception e ) {
			throw new UCMException( e.getMessage(), UCMType.CREATION_FAILED );
		}
	}

	/************************************************************************
	 * STREAM FUNCTIONALITY
	 ************************************************************************/

        private final String rx_rebase_in_progress = "^Rebase operation in progress on stream";
	private static final String rx_stream_load = "\\s*Error: stream not found\\s*";

	public void recommendBaseline( String stream, String baseline ) throws UCMException
	{
		String cmd = "chstream -recommend " + baseline + " " + stream;
		try {
			Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not recommend Baseline: "
					+ e.getMessage(), e.getMessage());
		}

	}

	public String getRecommendedBaselines( String stream )
	{
		String cmd = "desc -fmt %[rec_bls]p " + stream;
		return Cleartool.run(cmd).stdoutBuffer.toString();
	}


	public String getStreamFromView( String viewtag ) throws UCMException
	{
	    try {
	        String fqstreamstr = Cleartool.run( "lsstream -fmt %Xn -view " + viewtag ).stdoutBuffer.toString();
	        return fqstreamstr;
	    } catch( AbnormalProcessTerminationException e ) {
	        throw new UCMException( "Could not get Stream from view " + viewtag + ": " + e );
	    }
	}


	public void createStream( String pstream, String nstream, boolean readonly, String baseline )
	{
		logger.debug( "Creating stream " + nstream + " as child of " + pstream );

		String cmd = "mkstream -in " + pstream + " " + ( baseline != null ? "-baseline " + baseline + " " : "" ) + ( readonly ? "-readonly " : "" ) + nstream;
		Cleartool.run( cmd );
	}
	
	public void createIntegrationStream( String name, Project project, Component ... components ) throws UCMException {
		String cmd = "mkstream -integration -in " + project.getFullyQualifiedName() + " -baseline ";
		for( Component c : components ) {
			cmd += c.getShortname() + "_INITIAL@" + project.getPVob() + ", ";
		}
		cmd = cmd.substring( 0, ( cmd.length() - 2 ) ) + " " + name + "@" + project.getPVob();
		
		try {
			Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException( "Could not create integration stream: " + e.getMessage(), UCMType.CREATION_FAILED );
		}
	}

	public void generate( String stream )
	{
		String cmd = "chstream -generate " + stream;
		Cleartool.run(cmd);
	}

	public boolean streamExists( String fqname )
	{
		String cmd = "describe " + fqname;
		try {
			Cleartool.run(cmd);
			return true;
		} catch (AbnormalProcessTerminationException e) {
			return false;
		}
	}


	public boolean rebaseStream( String viewtag, String stream, String baseline, boolean complete )
	{
		logger.debug( "Rebasing " + viewtag );

		String cmd = "rebase " + ( complete ? "-complete " : "" ) + " -force -view " + viewtag + " -stream " + stream + " -baseline " + baseline;
		CmdResult res = Cleartool.run( cmd );

		if( res.stdoutBuffer.toString().matches( "^No rebase needed.*" ) )
		{
			return false;
		}

		return true;
	}

	public boolean isRebasing( String stream )
	{
		String cmd = "rebase -status -stream " + stream;
		String result = Cleartool.run(cmd).stdoutBuffer.toString();
		if (result.matches(rx_rebase_in_progress)) {
			return true;
		}

		return false;
	}

	public void cancelRebase( String stream )
	{
		String cmd = "rebase -cancel -force -stream " + stream;
		Cleartool.run(cmd);
	}

	public List<String> getLatestBaselines( String stream )
	{
		String cmd = "desc -fmt %[latest_bls]Xp " + stream;
		String[] t = Cleartool.run(cmd).stdoutBuffer.toString().split(" ");
		List<String> bls = new ArrayList<String>();
		for (String s : t) {
			if (s.matches("\\S+")) {
				bls.add(s);
			}
		}

		return bls;
	}

	public String loadStream( String stream ) throws UCMException
	{
		logger.debug( "Loading " + stream );

		CmdResult res = null;

		String cmd = "describe -fmt %[name]p" + UCMStrategyInterface.delim
				+ "%[project]Xp" + UCMStrategyInterface.delim
				+ "%X[def_deliver_tgt]p" + UCMStrategyInterface.delim
				+ "%[read_only]p " + stream;
		try {
			res = Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			if (e.getMessage().matches(rx_stream_load)) {
				throw new UCMException("The component \"" + stream
						+ "\", does not exist.", UCMType.LOAD_FAILED);
			} else {
				throw new UCMException(e.getMessage(), e.getMessage(),
						UCMType.LOAD_FAILED);
			}
		}

		return res.stdoutBuffer.toString();
	}

	/**
	 * @param fqstream
	 *            - Fully Qulyfied Stream name (stream:name@\P_VOB)
	 * @return List<Stream> {@link Stream}
	 */
	@Override
	public List<Stream> getChildStreams(String fqstream) throws UCMException {
		logger.debug("");
		CmdResult res = null;

		String cmd = "desc -fmt %[dstreams]CXp " + fqstream;
		try {
			res = Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException(e.getMessage());
		}

		List<Stream> streams = new ArrayList<Stream>();
		String[] strms = res.stdoutBuffer.toString().split(", ");
                for (String stream : strms) {
                    System.out.println(stream);
			streams.add(UCMEntity.getStream(stream));
		}

		return streams;
	}

	/************************************************************************
	 * VERSION FUNCTIONALITY
	 ************************************************************************/

	public String getVersion( String version, String separator )
	{
		String cmd = "desc -fmt %d" + separator + "%u" + separator + "%h" + separator + "%c" + separator + "%Rf" + separator + "%m" + separator + "%Vn" + separator + "%Xn \"" + version + "\"";
		return Cleartool.run( cmd ).stdoutBuffer.toString();
	}

	public String getVersionExtension(String file, File viewroot)
			throws UCMException {
		if (!(new File(file).exists())) {
			throw new UCMException("The file " + file + " does not exist.");
		}

		String cmd = "desc -fmt %Xp " + file;
		CmdResult r = Cleartool.run(cmd, viewroot);
		return r.stdoutBuffer.toString();
	}

	/************************************************************************
	 * TAG FUNCTIONALITY
	 ************************************************************************/

	private static final Pattern pattern_tags = Pattern.compile( "^\\s*(tag@\\d+@" + rx_ccdef_allowed + "+)\\s*->\\s*\"(.*?)\"\\s*$" );
	private static final Pattern pattern_hlink = Pattern.compile( "^\\s*(" + rx_ccdef_allowed + "+@\\d+@" + rx_ccdef_allowed + "+)\\s*->\\s*\"*(.*?)\"*\\s*$" );
	private static final Pattern pattern_remove_verbose_tag = Pattern.compile( "^.*?\"(.*)\".*?$" );
	private static final Pattern pattern_hlink_type_missing = Pattern.compile( ".*Error: hyperlink type \"(.*?)\" not found in VOB.*" );


	//public List<Tuple<String, String>> GetTags( String fqname ) throws UCMException
	public List<String[]> getTags( String fqname ) throws UCMException
	{
		logger.trace_function();
		logger.debug(fqname);

		String cmd = "describe -ahlink " + __TAG_NAME + " -l " + fqname;
		CmdResult res = null;
		try {
			res = Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			Matcher match = pattern_hlink_type_missing.matcher(e.getMessage());
			if (match.find()) {
				UCM.addMessage("The Hyperlink type \""
						+ match.group(1)
						+ "\" was not found.\nInstallation: \"cleartool mkhltype "
						+ __TAG_NAME
						+ " -c \"Hyperlink type for tagging entities\"\"");
				throw new UCMException("ClearCase hyperlink type \""
						+ match.group(1) + "\" was not found. ",
						e.getMessage(), UCMType.UNKNOWN_HLINK_TYPE);
			}

			throw e;
		}

		List<String> list = res.stdoutList;

		// List<Tuple<String, String>> tags = new ArrayList<Tuple<String,
		// String>>();
		List<String[]> tags = new ArrayList<String[]>();

		/* There are tags */
		if (list.size() > 2) {
			for (int i = 2; i < list.size(); i++) {
				if (UCM.isVerbose()) {
					logger.debug("[" + i + "]" + list.get(i));
				}
				Matcher match = pattern_tags.matcher(list.get(i));
				if (match.find()) {
					// tags.add( new Tuple<String, String>( match.group( 1 ),
					// match.group( 2 ) ) );
					tags.add(new String[] { match.group(1), match.group(2) });
				}
			}
		}

		return tags;
	}

	public String getTag( String fqname )
	{
		// TODO Auto-generated method stub
		return null;
	}

	public String newTag( UCMEntity entity, String cgi ) throws UCMException
	{
		logger.trace_function();
		logger.debug( entity.getFullyQualifiedName() );

		String cmd = "mkhlink -ttext \"" + cgi + "\" " + __TAG_NAME + " " + entity.getFullyQualifiedName();

		CmdResult res = null;
		try {
			res = Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			Matcher match = pattern_hlink_type_missing.matcher(e.getMessage());
			if (match.find()) {
				UCM.addMessage("The Hyperlink type \""
						+ match.group(1)
						+ "\" was not found.\nInstallation: \"cleartool mkhltype "
						+ __TAG_NAME
						+ " -c \"Hyperlink type for tagging entities\"\"");
				throw new UCMException("ClearCase hyperlink type \""
						+ match.group(1) + "\" was not found.", e.getMessage(),
						UCMType.UNKNOWN_HLINK_TYPE);
			}

			throw e;
		}

		String tag = res.stdoutBuffer.toString();

		Matcher match = pattern_remove_verbose_tag.matcher(tag);
		if (!match.find()) {
			throw new UCMException("Could not create tag",
					UCMType.TAG_CREATION_FAILED);
		}

		return match.group(1);
	}


	public void deleteTag( String fqname )
	{
		// TODO Auto-generated method stub

	}


	public void deleteTagsWithID( String tagType, String tagID, String entity ) throws UCMException
	{
		logger.trace_function();
		logger.debug( tagType + tagID );

		List<String[]> list = getTags( entity );
		logger.debug( list.size() + " Tags!" );

		for( String[] t : list )
		{
			if( UCM.isVerbose() )
			{
				logger.debug( "Testing " + t[0] + " > " + t[1] );
			}
			if (t[1].matches("^.*tagtype=" + tagType + ".*$")
					&& t[1].matches("^.*tagid=" + tagID + ".*$")) {
				String cmd = "rmhlink " + t[0];
				Cleartool.run(cmd);
			}
		}

	}

	public String putTag( String fqname, String keyval, UCMEntity entity )
	{
		// TODO Auto-generated method stub
		return null;
	}

	public String loadHyperLink( String fqname, File dir ) throws UCMException
	{
		String cmd = "describe " + fqname;

		CmdResult res = null;
		try {
			res = Cleartool.run(cmd, dir);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not load hyperlink", e.getMessage());
		}

		return res.stdoutBuffer.toString();
	}

	public List<Tuple<String, String>> getHlinks(String fqname,
			String hlinkType, File dir) throws UCMException {
		String cmd = "describe -ahlink " + hlinkType + " -l " + fqname;

		CmdResult res = null;
		try {
			res = Cleartool.run(cmd, dir);
		} catch (AbnormalProcessTerminationException e) {
			Matcher match = pattern_hlink_type_missing.matcher(e.getMessage());
			if (match.find()) {
				UCM.addMessage("The Hyperlink type \""
						+ match.group(1)
						+ "\" was not found.\nInstallation: \"cleartool mkhltype "
						+ hlinkType + " -nc\"");
				throw new UCMException("ClearCase hyperlink type \""
						+ match.group(1) + "\" was not found. ",
						e.getMessage(), UCMType.UNKNOWN_HLINK_TYPE);
			}

			throw e;
		}

		List<String> list = res.stdoutList;

		List<Tuple<String, String>> hlinks = new ArrayList<Tuple<String, String>>();

		/* There are elements */
		if (list.size() > 2) {
			for (int i = 2; i < list.size(); i++) {
				logger.debug("[" + i + "]" + list.get(i));
				Matcher match = pattern_hlink.matcher(list.get(i));
				if (match.find()) {
					hlinks.add(new Tuple<String, String>(match.group(1).trim(),
							match.group(2).trim()));
				}
			}
		}

		return hlinks;
	}

	/************************************************************************
	 * SNAPSHOT VIEW FUNCTIONALITY
	 ************************************************************************/

	// View uuid: b2b51957.41a24c7a.b998.67:b8:4a:93:60:01
	private static final Pattern pattern_view_uuid = Pattern.compile(
			"^\\s*View uuid:\\s*([\\w\\.:]+).*$", Pattern.CASE_INSENSITIVE
					| Pattern.MULTILINE);
	protected static final Pattern rx_view_uuid = Pattern
			.compile("view_uuid:(.*)");
	private final String rx_co_file = ".*CHECKEDOUT$";
	private final String rx_ctr_file = ".*\\.contrib";
	private final String rx_keep_file = ".*\\.keep$";

	public void checkViewContext( File dir ) throws UCMException
	{
		logger.trace_function();
		logger.debug("");

		String cmd = "pwv -root";
		try {
			Cleartool.run(cmd, dir).stdoutBuffer.toString();
		} catch (AbnormalProcessTerminationException e) {
			if (e.getMessage().equalsIgnoreCase(
					"cleartool: Error: operation requires a view")) {
				throw new UCMException("operation requires a view",
						e.getMessage());
			}

			throw e;
		}
	}

	public boolean IsVob(File dir) {
		logger.debug("Testing " + dir);

		String cmd = "lsvob \\" + dir.getName();
		try {
			Cleartool.run(cmd);
		} catch (Exception e) {
			logger.debug("E=" + e.getMessage());
			return false;
		}

		return true;
	}

	public List<String> ListVobs(File viewroot) {
		logger.debug("Listing vobs at " + viewroot);

		File[] files = viewroot.listFiles();
		List<String> vobs = new ArrayList<String>();

		for (File f : files) {
			if (f.isDirectory()) {
				if (IsVob(f)) {
					vobs.add(f.getName());
				}
			}
		}

		return vobs;
	}


	public void makeSnapshotView( String stream, File viewroot, String viewtag ) throws UCMException
	{
		logger.debug( "The view \"" + viewtag + "\" in \"" + viewroot + "\"" );

		if( viewroot.exists() )
		{
			IO.deleteDirectory( viewroot );
		}

		this.generate(stream);

		String cmd = "mkview -snap -tag " + viewtag + " -stream " + stream
				+ " \"" + viewroot.getAbsolutePath() + "\"";

		try {
			Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			logger.warning("Could not create snapshot view \"" + viewtag + "\"");
			throw new UCMException("Could not create snapshot view \""
					+ viewtag + "\"", e.getMessage(), UCMType.VIEW_ERROR);
		}
	}

	public String viewUpdate( File viewroot, boolean overwrite, String loadrules )
	{
		logger.debug( viewroot.getAbsolutePath() );

		String cmd = "setcs -stream";
		Cleartool.run(cmd, viewroot);

		logger.debug("Updating view");

		cmd = "update -force " + (overwrite ? " -overwrite " : "") + loadrules;
		return Cleartool.run(cmd, viewroot, true).stdoutBuffer.toString();

	}

	public void regenerateViewDotDat( File dir, String viewtag ) throws UCMException
	{
		logger.trace_function();
		logger.debug(dir + ", " + viewtag);

		File viewdat = new File(dir + File.separator + "view.dat");

		if (viewdat.exists()) {
			throw new UCMException(
					"view.dat file already exist. No need for regenrating.");
		}

		String cmd = "lsview -l " + viewtag;
		/* TODO Check this functions behavior, if the view doesn't exist */
		String result = Cleartool.run(cmd).stdoutBuffer.toString();

		// System.out.println(result);

		Matcher match = pattern_view_uuid.matcher(result);
		if (!match.find()) {
			logger.warning("The UUID of the view " + viewtag
					+ " does not exist!");
			throw new UCMException("The UUID of the view " + viewtag
					+ " does not exist!");
		}

		String uuid = match.group(1);

		cmd = "lsview -uuid " + uuid;

		try {
			Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Unable to read the UUID(" + uuid
					+ ") from view tag " + viewtag, e.getMessage());
		}

		if (dir.exists()) {
			logger.warning("The view root, " + dir
					+ ",  already exists - reuse may be problematic");
		} else {
			dir.mkdirs();
		}

		try {
			FileOutputStream fos = new FileOutputStream(viewdat);
			fos.write(("ws_oid:00000000000000000000000000000000 view_uuid:" + uuid)
					.getBytes());
			fos.close();
		} catch (IOException e) {
			throw new UCMException("Could not create view.dat", e.getMessage(),
					UCMType.VIEW_ERROR);
		}

		/* TODO Too much windows.... */
		// cmd = "attrib +h +r " + viewdat;
		if (!viewdat.setReadOnly()) {
			logger.warning("Could set view.dat as read only");
			throw new UCMException("Could set view.dat as read only");
		}
		// viewdat.set
		// Command.run( cmd );
	}

	public boolean viewExists( String viewtag )
	{
		logger.trace_function();
		logger.debug(viewtag);

		String cmd = "lsview " + viewtag;

		try {
			String s = Cleartool.run(cmd).stdoutBuffer.toString();
			logger.debug("---->" + s);
			return true;
		} catch (AbnormalProcessTerminationException e) {
			logger.debug("---->" + e.getMessage());
			return false;
		}
	}

	public Map<String, Integer> swipeView( File viewroot, boolean excludeRoot )
	{
		logger.debug( viewroot.toString() );

		File[] files = viewroot.listFiles();
		String fls = "";
		List<File> other = new ArrayList<File>();
		List<File> root = new ArrayList<File>();

		for (File f : files) {
			if (!f.canWrite()) {
				logger.debug(f + " is write protected.");
				continue;
			}

			if (f.isDirectory()) {
				if (IsVob(f)) {
					fls += "\"" + f.getAbsolutePath() + "\" ";
				} else {
					other.add(f);
				}
			} else {
				if (f.getName().equalsIgnoreCase("view.dat")) {
					continue;
				}
				root.add(f);
			}
		}

		/* Remove all other dirs */
		for (File f : other) {
			if (UCM.isVerbose()) {
				logger.log("Removing " + f);
			}
			net.praqma.util.io.IO.deleteDirectory(f);
		}

		Map<String, Integer> info = new HashMap<String, Integer>();
		info.put("success", 1);

		if (fls.length() == 0) {
			logger.debug("No files to delete");
			return info;
		}

		String cmd = "ls -short -recurse -view_only " + fls;
		List<String> result = Cleartool.run(cmd).stdoutList;
		List<File> rnew = new ArrayList<File>();

		if (!excludeRoot) {
			rnew.addAll(root);
		}

		int total = result.size() + rnew.size();

		info.put("total", total);

		for (String s : result) {
			if (s.matches(rx_co_file) || s.matches(rx_keep_file)
					|| s.matches(rx_ctr_file)) {
				continue;
			}

			rnew.add(new File(s));
		}

		logger.debug("Found " + total + " files, of which "
				+ (total - rnew.size()) + " were CO, CTR or KEEP's.");

		List<File> dirs = new ArrayList<File>();
		int dircount = 0;
		int filecount = 0;

		/* Removing view private files, saving directories for later */
		for (File f : rnew) {
			// logger.debug( "FILE=" + f );

			if (f.exists()) {
				if (f.isDirectory()) {
					dirs.add(f);
				} else {
					// logger.debug( "Deleting " + f );
					f.delete();
					filecount++;
				}
			} else {
				logger.debug("The file " + f + " does not exist.");
			}
		}

		info.put("files_deleted", filecount);

		/* TODO Remove the directories, somehow!? Only the empty!? */
		for (File d : dirs) {
			try {
				d.delete();
				dircount++;
			} catch (SecurityException e) {
				logger.log("Unable to delete \"" + d
						+ "\". Probably not empty.");
			}
		}

		info.put("dirs_deleted", dircount);

		logger.print("Deleted " + dircount + " director"
				+ (dircount == 1 ? "y" : "ies") + " and " + filecount + " file"
				+ (filecount == 1 ? "" : "s"));

		if (dircount + filecount == total) {
			info.put("success", 1);
		} else {
			logger.warning("Some files were not deleted.");
			info.put("success", 0);
		}

		return info;
	}

	@Override
	public File getCurrentViewRoot( File viewroot )
	{
		logger.trace_function();
		logger.debug(viewroot.getAbsolutePath());

		String wvroot = Cleartool.run("pwv -root", viewroot).stdoutBuffer
				.toString();

		return new File(wvroot);
	}

	public String viewrootIsValid( File viewroot ) throws UCMException
	{
		logger.debug( viewroot.getAbsolutePath() );

		File viewdotdatpname = new File( viewroot + File.separator + "view.dat" );

		logger.debug( "The view file = " + viewdotdatpname );

		FileReader fr = null;
		try {
			fr = new FileReader(viewdotdatpname);
		} catch (FileNotFoundException e1) {
			logger.warning("\"" + viewdotdatpname + "\" not found!");
			throw new UCMException("The file could not be found. ",
					e1.getMessage());
		}

		BufferedReader br = new BufferedReader(fr);
		String line;
		StringBuffer result = new StringBuffer();
		try {
			while ((line = br.readLine()) != null) {
				result.append(line);
			}
		} catch (IOException e) {
			logger.warning("Couldn't read lines from " + viewdotdatpname);
			throw new UCMException("Could not read lines", e.getMessage());
		}

		logger.debug("FILE CONTENT=" + result.toString());

		Matcher match = rx_view_uuid.matcher(result.toString());

		String uuid = "";

		if (match.find()) {
			/* A match is found */
			uuid = match.group(1).trim();
		} else {
			logger.log("UUID not found!", "warning");
			throw new UCMException("UUID not found");
		}

		String cmd = "lsview -s -uuid " + uuid;
		String viewtag = Cleartool.run(cmd).stdoutBuffer.toString().trim();

		return viewtag;
	}
	
	public void createView( String tag, String path, boolean snapshotView, Stream stream ) throws UCMException {
		logger.info("Creating " + tag);
		String cmd = "mkview -tag " + tag + ( snapshotView ? " -snapshot" : "" ) + ( stream != null ? " -stream " + stream.getFullyQualifiedName() : "" ) + " -stgloc " + ( path != null ? path : "-auto" );
		
		try {
			Cleartool.run(cmd);
		} catch( Exception e ) {
			throw new UCMException(e.getMessage(), UCMType.CREATION_FAILED);
		}
	}
	
	public void removeView( UCMView view ) throws UCMException {
		String cmd = "rmview -force " + ( view.isDynamicView() ? "-tag " + view.GetViewtag() : view.getStorageLocation() );
		
		try {
			Cleartool.run(cmd);
		} catch( Exception e ) {
			throw new UCMException("Could not remove view: " + e.getMessage());
		}
	}
	
	public static final Pattern rx_view_get_path = Pattern.compile("^\\s*Global path:\\s*(.*?)\\s*$");
	
	public Map<String, String> loadView( UCMView view ) throws UCMException {
		logger.info("Loading view " + view);
		
		String cmd = "lsview -l " + view.GetViewtag();
		
		Map<String, String> a = new HashMap<String, String>();
		
		try {
			CmdResult r = Cleartool.run(cmd);
			
			for( String s : r.stdoutList ) {
				if( s.contains("Global path") ) {
					Matcher m = rx_view_get_path.matcher(s);
					if(m.find()) {
						a.put("pathname", m.group(1));
					}
				}
			}
			
		} catch( Exception e ) {
			throw new UCMException( "Could not load Vob: " + e.getMessage() );
		}
		
		return a;
	}
	
	/*****************************
	 * Vobs
	 *****************************/
	
	public static final Pattern rx_vob_get_path = Pattern.compile("^\\s*VOB storage global pathname\\s*\"(.*?)\"\\s*$");
	
	public void createVob( String vobname, boolean UCMProject, String path, String comment ) throws UCMException {
		logger.info("Creating vob " + vobname);
		
		String cmd = "mkvob -tag " + vobname + ( UCMProject ? " -ucmproject" : "" ) + ( comment != null ? " -c \"" + comment + "\"" : "" ) + " -stgloc " + ( path != null ? path : "-auto" );
		
		try {
			Cleartool.run(cmd);
		} catch( Exception e ) {
			throw new UCMException(e.getMessage(), UCMType.CREATION_FAILED);
		}
	}
	
	public List<PVob> getVobs(Region region) {
		String cmd = "lsvob -s" + (region != null ? " -region " + region.getName() : "");
		CmdResult cr = Cleartool.run(cmd);

		List<PVob> vobs = new ArrayList<PVob>();
		for (String s : cr.stdoutList) {
			vobs.add(new PVob(s));
		}

		return vobs;
	}
	
	public Map<String, String> loadVob( Vob vob ) throws UCMException {
		logger.info("Loading vob " + vob);
		
		String cmd = "describe vob:" + vob;
		
		Map<String, String> a = new HashMap<String, String>();
		
		try {
			CmdResult r = Cleartool.run(cmd);
			
			for( String s : r.stdoutList ) {
				if( s.contains("VOB storage global pathname") ) {
					Matcher m = rx_vob_get_path.matcher(s);
					if(m.find()) {
						a.put("pathname", m.group(1));
					}
				}
			}
			
		} catch( Exception e ) {
			throw new UCMException( "Could not load Vob: " + e.getMessage() );
		}
		
		return a;
	}
	
	public void mountVob( Vob vob ) throws UCMException {
		logger.info("Mounting vob " + vob);
		
		String cmd = "mount " + vob;
		try {
			Cleartool.run(cmd);
		} catch( Exception e ) {
			if(e.getMessage().contains( "is already mounted" )) {
				/* No op */
				return;
			}
			
			throw new UCMException( "Could not mount Vob " + vob + ": " + e.getMessage());
		}
	}
	
	public void unmountVob( Vob vob ) throws UCMException {
		logger.info("UnMounting vob " + vob);
		
		String cmd = "umount " + vob;
		try {
			Cleartool.run(cmd);
		} catch( Exception e ) {
			throw new UCMException( "Could not unmount Vob " + vob + ": " + e.getMessage());
		}
	}


	public int getVobCount() {
	    String cmd = "lsvob -short";
	    return Cleartool.run( cmd ).stdoutList.size();
	}
	
	public void removeVob( Vob vob ) throws UCMException {
		String cmd = "rmvob -force " + vob.getStorageLocation();
		
		try {
			Cleartool.run(cmd);
		} catch( Exception e ) {
			throw new UCMException( "Could remove Vob " + vob + ": " + e.getMessage());
		}
	}

	/*****************************
	 * Attributes
	 *****************************/

	/**
	 *
	 * @param fqname
	 * @param attribute
	 * @return String
	 * @throws UCMException
	 */
	public String getAttribute(String fqname, String attribute)
			throws UCMException {
		String cmd = "describe -aattr " + attribute + " -l " + fqname;

		CmdResult res = null;
		try {
			res = Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not find attribute with name: "
					+ attribute + " on " + fqname + ". Recieved: "
					+ e.getMessage(), e.getMessage());
		}

		return res.toString();
	}

	private static final String rx_attr_find = "^\\s*\\S+\\s*=\\s*\\S*\\s*$";

	public Map<String, String> getAttributes(String fqname) throws UCMException {
		return getAttributes(fqname, null);
	}

	public Map<String, String> getAttributes(String fqname, File dir)
			throws UCMException {
		logger.debug("Getting attributes for " + fqname);

		String cmd = "describe -aattr -all " + fqname;

		CmdResult res = null;
		try {
			res = Cleartool.run(cmd, dir);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not find attributes on " + fqname
					+ ". Recieved: " + e.getMessage(), e.getMessage());
		}

		Map<String, String> atts = new HashMap<String, String>();

		for (String s : res.stdoutList) {
			/* A valid attribute */
			if (s.matches(rx_attr_find)) {
				String[] data = s.split("=");
				atts.put(data[0].trim(), data[1].trim());
			}
		}

		return atts;
	}

	public void setAttribute(String fqname, String attribute, String value)
			throws UCMException {
		logger.debug("Setting attribute " + attribute + "=" + value + " for "
				+ fqname);

		String cmd = "mkattr -replace " + attribute + " " + value + " "
				+ fqname;
		try {
			Cleartool.run(cmd);
		} catch (AbnormalProcessTerminationException e) {
			throw new UCMException("Could not create the attribute "
					+ attribute, e.getMessage());
		}
	}	
	
	private static final Pattern __FIND_VIEW_ROOT = Pattern.compile("^\\s*\\**\\s*\\w+\\s*(.+)$");

	@Override
	public List<UCMView> getViews(Region region) {
	    String cmd = "lsview" + ( region != null ? " -region " + region.getName() : "" );
	    CmdResult cr = Cleartool.run( cmd );
	    
	    List<UCMView> views = new ArrayList<UCMView>();
	    for( String s : cr.stdoutList ) {
		
		/* Pre process views */
		Matcher m = __FIND_VIEW_ROOT.matcher(s);
		if( m.find() ) {
		    views.add( new UCMView( m.group(1).trim() ) );
		}
	    }
	    
	    return views;
	}
	



	/*****************************
	 * OTHER STUFF
	 *****************************/
	// public List<Stream> getChildStreams(){
	// CmdResult res = null;
	// String cmd = "lsvob"
	//
	// return null;
	// }

	public String getXML()
	{
		// TODO Auto-generated method stub
		return null;
	}


	public void saveState()
	{
		// TODO Auto-generated method stub

	}


}