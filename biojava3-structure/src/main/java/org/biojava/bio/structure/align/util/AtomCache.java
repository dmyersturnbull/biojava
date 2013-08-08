/*
 * BioJava development code
 * 
 * This code may be freely distributed and modified under the terms of the GNU Lesser General Public Licence. This
 * should be distributed with the code. If you do not have a copy, see:
 * 
 * http://www.gnu.org/copyleft/lesser.html
 * 
 * Copyright for this code is held jointly by the individual authors. These should be listed in @author doc comments.
 * 
 * For more information on the BioJava project and its aims, or to join the biojava-l mailing list, visit the home page
 * at:
 * 
 * http://www.biojava.org/
 */
package org.biojava.bio.structure.align.util;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.biojava.bio.structure.Atom;
import org.biojava.bio.structure.AtomPositionMap;
import org.biojava.bio.structure.Chain;
import org.biojava.bio.structure.Group;
import org.biojava.bio.structure.ResidueRange;
import org.biojava.bio.structure.Structure;
import org.biojava.bio.structure.StructureException;
import org.biojava.bio.structure.StructureTools;
import org.biojava.bio.structure.align.ce.AbstractUserArgumentProcessor;
import org.biojava.bio.structure.align.client.StructureName;
import org.biojava.bio.structure.cath.CathDomain;
import org.biojava.bio.structure.cath.CathInstallation;
import org.biojava.bio.structure.cath.CathSegment;
import org.biojava.bio.structure.domain.PDPProvider;
import org.biojava.bio.structure.domain.RemotePDPProvider;
import org.biojava.bio.structure.io.FileParsingParameters;
import org.biojava.bio.structure.io.PDBFileReader;
import org.biojava.bio.structure.scop.CachedRemoteScopInstallation;
import org.biojava.bio.structure.scop.ScopDatabase;
import org.biojava.bio.structure.scop.ScopDescription;
import org.biojava.bio.structure.scop.ScopDomain;
import org.biojava.bio.structure.scop.ScopFactory;
import org.biojava3.core.util.InputStreamProvider;
import org.biojava3.structure.StructureIO;

/**
 * A utility class that provides easy access to Structure objects. If you are running a script that is frequently
 * re-using the same PDB structures, the AtomCache keeps an in-memory cache of the files for quicker access. The cache
 * is a soft-cache, this means it won't cause out of memory exceptions, but garbage collects the data if the Java
 * virtual machine needs to free up space. The AtomCache is thread-safe.
 * 
 * @author Andreas Prlic
 * @author Spencer Bliven
 * @author Peter Rose
 * @since 3.0
 */
public class AtomCache {

	public static final String BIOL_ASSEMBLY_IDENTIFIER = "BIO:";
	public static final String CHAIN_NR_SYMBOL = ":";
	public static final String CHAIN_SPLIT_SYMBOL = ".";

	public static final String PDP_DOMAIN_IDENTIFIER = "PDP:";

	public static final Pattern scopIDregex = Pattern.compile("d(....)(.)(.)");

	public static final String UNDERSCORE = "_";

	private static final String FILE_SEPARATOR = System.getProperty("file.separator");

	private boolean fetchCurrent;

	private boolean fetchFileEvenIfObsolete;

	private ScopDatabase scopInstallation;
	protected FileParsingParameters params;
	protected PDPProvider pdpprovider;

	boolean autoFetch;

	String cachePath;

	// make sure IDs are loaded uniquely
	Collection<String> currentlyLoading = Collections.synchronizedCollection(new TreeSet<String>());

	boolean isSplit;
	String path;

	boolean strictSCOP;

	/**
	 * Default AtomCache constructor.
	 * 
	 * Usually stores files in a temp directory, but this can be overriden by setting the PDB_DIR variable at runtime.
	 * 
	 * @see UserConfiguration#UserConfiguration()
	 */
	public AtomCache() {
		this(new UserConfiguration());
	}

	/**
	 * Creates an instance of an AtomCache that is pointed to the a particular path in the file system.
	 * 
	 * @param pdbFilePath
	 *            a directory in the file system to use as a location to cache files.
	 * @param isSplit
	 *            a flag to indicate if the directory organisation is "split" as on the PDB ftp servers, or if all files
	 *            are contained in one directory.
	 */
	public AtomCache(String pdbFilePath, final boolean isSplit) {

		if (!pdbFilePath.endsWith(FILE_SEPARATOR)) {
			pdbFilePath += FILE_SEPARATOR;
		}

		// we are caching the binary files that contain the PDBs gzipped
		// that is the most memory efficient way of caching...
		// set the input stream provider to caching mode
		System.setProperty(InputStreamProvider.CACHE_PROPERTY, "true");

		path = pdbFilePath;
		System.setProperty(AbstractUserArgumentProcessor.PDB_DIR, path);

		String tmpCache = System.getProperty(AbstractUserArgumentProcessor.CACHE_DIR);
		if (tmpCache == null || tmpCache.equals("")) {
			tmpCache = pdbFilePath;
		}

		cachePath = tmpCache;
		System.setProperty(AbstractUserArgumentProcessor.CACHE_DIR, cachePath);

		// this.cache = cache;
		this.isSplit = isSplit;

		autoFetch = true;
		fetchFileEvenIfObsolete = false;
		fetchCurrent = false;

		currentlyLoading.clear();
		params = new FileParsingParameters();

		// we don't need this here
		params.setAlignSeqRes(false);
		// no secstruc either
		params.setParseSecStruc(false);
		//

		strictSCOP = true;

		scopInstallation = null;
	}

	/**
	 * Creates a new AtomCache object based on the provided UserConfiguration.
	 * 
	 * @param config
	 *            the UserConfiguration to use for this cache.
	 */
	public AtomCache(final UserConfiguration config) {
		this(config.getPdbFilePath(), config.isSplit());
		autoFetch = config.getAutoFetch();
	}

	/**
	 * Returns the CA atoms for the provided name. See {@link #getStructure(String)} for supported naming conventions.
	 * 
	 * @param name
	 * @return an array of Atoms.
	 * @throws IOException
	 * @throws StructureException
	 */
	public Atom[] getAtoms(final String name) throws IOException, StructureException {

		Atom[] atoms = null;

		// System.out.println("loading " + name);
		Structure s = null;
		try {

			s = getStructure(name);

		} catch (final StructureException ex) {
			System.err.println("error getting Structure for " + name);
			throw new StructureException(ex.getMessage(), ex);
		}

		atoms = StructureTools.getAtomCAArray(s);

		/*
		 * synchronized (cache){ cache.put(name, atoms); }
		 */

		return atoms;
	}

	/**
	 * Returns the CA atoms for the provided name. See {@link #getStructure(String)} for supported naming conventions.
	 * 
	 * @param name
	 * @param clone
	 *            flag to make sure that the atoms are getting coned
	 * @return an array of Atoms.
	 * @throws IOException
	 * @throws StructureException
	 * @deprecated does the same as {@link #getAtoms(String)} ;
	 */
	@Deprecated
	public Atom[] getAtoms(final String name, final boolean clone) throws IOException, StructureException {
		final Atom[] atoms = getAtoms(name);

		if (clone) {
			return StructureTools.cloneCAArray(atoms);
		}
		return atoms;

	}

	/**
	 * Loads the biological assembly for a given PDB ID and bioAssemblyId. If a bioAssemblyId > 0 is specified, the
	 * corresponding biological assembly file will be loaded. Note, the number of available biological unit files
	 * varies. Many entries don't have a biological assembly specified (i.e. NMR structures), many entries have only one
	 * biological assembly (bioAssemblyId=1), and a few structures have multiple biological assemblies. Set
	 * bioAssemblyFallback to true, to download the original PDB file in cases that a biological assembly file is not
	 * available.
	 * 
	 * @param pdbId
	 *            the PDB ID
	 * @param bioAssemblyId
	 *            the ID of the biological assembly
	 * @param bioAssemblyFallback
	 *            if true, try reading original PDB file in case the biological assembly file is not available
	 * @return a structure object
	 * @throws IOException
	 * @throws StructureException
	 * @author Peter Rose
	 * @since 3.2
	 */
	public Structure getBiologicalAssembly(final String pdbId, final int bioAssemblyId,
			final boolean bioAssemblyFallback) throws StructureException, IOException {
		Structure s;
		if (bioAssemblyId < 1) {
			throw new StructureException("bioAssemblyID must be greater than zero: " + pdbId + " bioAssemblyId "
					+ bioAssemblyId);
		}
		final PDBFileReader reader = new PDBFileReader();
		reader.setPath(path);
		reader.setPdbDirectorySplit(isSplit);
		reader.setAutoFetch(autoFetch);
		reader.setFetchFileEvenIfObsolete(fetchFileEvenIfObsolete);
		reader.setFetchCurrent(fetchCurrent);
		reader.setFileParsingParameters(params);
		reader.setBioAssemblyId(bioAssemblyId);
		reader.setBioAssemblyFallback(bioAssemblyFallback);
		s = reader.getStructureById(pdbId.toLowerCase());
		s.setPDBCode(pdbId);
		return s;
	}

	/**
	 * Loads the default biological unit (*.pdb1.gz) file. If it is not available, the original PDB file will be loaded,
	 * i.e., for NMR structures, where the original files is also the biological assembly.
	 * 
	 * @param pdbId
	 *            the PDB ID
	 * @return a structure object
	 * @throws IOException
	 * @throws StructureException
	 * @since 3.2
	 */
	public Structure getBiologicalUnit(final String pdbId) throws StructureException, IOException {
		final int bioAssemblyId = 1;
		final boolean bioAssemblyFallback = true;
		return getBiologicalAssembly(pdbId, bioAssemblyId, bioAssemblyFallback);
	}

	/**
	 * Returns the path that contains the caching file for utility data, such as domain definitons.
	 * 
	 * @return
	 */
	public String getCachePath() {
		if (cachePath == null || cachePath.equals("")) {
			return getPath();
		}
		return cachePath;
	}

	public FileParsingParameters getFileParsingParams() {
		return params;
	}

	/**
	 * Get the path that is used to cache PDB files.
	 * 
	 * @return path to a directory
	 */
	public String getPath() {
		return path;
	}

	public PDPProvider getPdpprovider() {
		return pdpprovider;
	}

	public ScopDatabase getScopInstallation() {
		if (scopInstallation == null) {
			scopInstallation = ScopFactory.getSCOP();
		}

		return scopInstallation;
	}

	/**
	 * Request a Structure based on a <i>name</i>.
	 * 
	 * <pre>
	 * 		Formal specification for how to specify the <i>name</i>:
	 * 
	 * 		name     := pdbID
	 * 		               | pdbID '.' chainID
	 * 		               | pdbID '.' range
	 * 		               | scopID
	 * 		range         := '('? range (',' range)? ')'?
	 * 		               | chainID
	 * 		               | chainID '_' resNum '-' resNum
	 * 		pdbID         := [0-9][a-zA-Z0-9]{3}
	 * 		chainID       := [a-zA-Z0-9]
	 * 		scopID        := 'd' pdbID [a-z_][0-9_]
	 * 		resNum        := [-+]?[0-9]+[A-Za-z]?
	 * 
	 * 
	 * 		Example structures:
	 * 		1TIM     #whole structure
	 * 		4HHB.C     #single chain
	 * 		4GCR.A_1-83     #one domain, by residue number
	 * 		3AA0.A,B     #two chains treated as one structure
	 * 		d2bq6a1     #scop domain
	 * </pre>
	 * 
	 * With the additional set of rules:
	 * 
	 * <ul>
	 * <li>If only a PDB code is provided, the whole structure will be return including ligands, but the first model
	 * only (for NMR).
	 * <li>Chain IDs are case sensitive, PDB ids are not. To specify a particular chain write as: 4hhb.A or 4HHB.A</li>
	 * <li>To specify a SCOP domain write a scopId e.g. d2bq6a1. Some flexibility can be allowed in SCOP domain names,
	 * see {@link #setStrictSCOP(boolean)}</li>
	 * <li>URLs are accepted as well</li>
	 * </ul>
	 * 
	 * @param name
	 * @return a Structure object, or null if name appears improperly formated (eg too short, etc)
	 * @throws IOException
	 *             The PDB file cannot be cached due to IO errors
	 * @throws StructureException
	 *             The name appeared valid but did not correspond to a structure. Also thrown by some submethods upon
	 *             errors, eg for poorly formatted subranges.
	 */
	public Structure getStructure(final String name) throws IOException, StructureException {

		if (name.length() < 4) {
			throw new IllegalArgumentException("Can't interpret IDs that are shorter than 4 residues!");
		}

		Structure n = null;

		boolean useChainNr = false;
		boolean useDomainInfo = false;
		String range = null;
		int chainNr = -1;

		try {

			final StructureName structureName = new StructureName(name);

			String pdbId = null;
			String chainId = null;

			if (name.length() == 4) {

				pdbId = name;
				final Structure s = loadStructureFromByPdbId(pdbId);
				return s;
			} else if (structureName.isScopName()) {

				// return based on SCOP domain ID
				return getStructureFromSCOPDomain(name);
			} else if (structureName.isCathID()) {
				return getStructureFromCATHDomain(structureName);
			} else if (name.length() == 6) {
				// name is PDB.CHAINID style (e.g. 4hhb.A)

				pdbId = name.substring(0, 4);
				if (name.substring(4, 5).equals(CHAIN_SPLIT_SYMBOL)) {
					chainId = name.substring(5, 6);
				} else if (name.substring(4, 5).equals(CHAIN_NR_SYMBOL)) {

					useChainNr = true;
					chainNr = Integer.parseInt(name.substring(5, 6));
				}

			} else if (name.startsWith("file:/") || name.startsWith("http:/")) {

				// this is a URL
				try {

					final URL url = new URL(name);

					return getStructureFromURL(url);

				} catch (final Exception e) {
					e.printStackTrace();
					return null;
				}

			} else if (structureName.isPDPDomain()) {

				// this is a PDP domain definition

				try {
					return getPDPStructure(name);
				} catch (final Exception e) {
					e.printStackTrace();
					return null;
				}
			} else if (name.startsWith(BIOL_ASSEMBLY_IDENTIFIER)) {

				try {

					return getBioAssembly(name);

				} catch (final Exception e) {
					e.printStackTrace();
					return null;
				}
			} else if (name.length() > 6 && !name.startsWith(PDP_DOMAIN_IDENTIFIER)
					&& (name.contains(CHAIN_NR_SYMBOL) || name.contains(UNDERSCORE))
					&& !(name.startsWith("file:/") || name.startsWith("http:/"))

			) {

				// this is a name + range

				pdbId = name.substring(0, 4);
				// this ID has domain split information...
				useDomainInfo = true;
				range = name.substring(5);

			}

			// System.out.println("got: >" + name + "< " + pdbId + " " + chainId + " useChainNr:" + useChainNr + " "
			// +chainNr + " useDomainInfo:" + useDomainInfo + " " + range);

			if (pdbId == null) {

				return null;
			}

			while (checkLoading(pdbId)) {
				// waiting for loading to be finished...

				try {
					Thread.sleep(100);
				} catch (final InterruptedException e) {
					System.err.println(e.getMessage());
				}

			}

			// long start = System.currentTimeMillis();

			final Structure s = loadStructureFromByPdbId(pdbId);

			// long end = System.currentTimeMillis();
			// System.out.println("time to load " + pdbId + " " + (end-start) + "\t  size :" +
			// StructureTools.getNrAtoms(s) + "\t cached: " + cache.size());

			if (chainId == null && chainNr < 0 && range == null) {
				// we only want the 1st model in this case
				n = StructureTools.getReducedStructure(s, -1);
			} else {

				if (useChainNr) {
					// System.out.println("using ChainNr");
					n = StructureTools.getReducedStructure(s, chainNr);
				} else if (useDomainInfo) {
					// System.out.println("calling getSubRanges");
					n = StructureTools.getSubRanges(s, range);
				} else {
					// System.out.println("reducing Chain Id " + chainId);
					n = StructureTools.getReducedStructure(s, chainId);
				}
			}

		} catch (final Exception e) {
			System.err.println("problem loading:" + name);
			e.printStackTrace();

			throw new StructureException(e.getMessage() + " while parsing " + name, e);

		}

		n.setName(name);
		return n;

	}

	/**
	 * Returns the representation of a {@link ScopDomain} as a BioJava {@link Structure} object.
	 * 
	 * @param domain
	 *            a SCOP domain
	 * @return a Structure object
	 * @throws IOException
	 * @throws StructureException
	 */
	public Structure getStructureForDomain(final ScopDomain domain) throws IOException, StructureException {
		if (scopInstallation == null) {
			scopInstallation = ScopFactory.getSCOP();
		}
		return getStructureForDomain(domain, scopInstallation);
	}

	/**
	 * Returns the representation of a {@link ScopDomain} as a BioJava {@link Structure} object.
	 * 
	 * @param domain
	 *            a SCOP domain
	 * @param scopDatabase
	 *            A {@link ScopDatabase} to use
	 * @return a Structure object
	 * @throws IOException
	 * @throws StructureException
	 */
	public Structure getStructureForDomain(final ScopDomain domain, final ScopDatabase scopDatabase)
			throws IOException, StructureException {
		return getStructureForDomain(domain, scopDatabase, false);
	}

	/**
	 * Returns the representation of a {@link ScopDomain} as a BioJava {@link Structure} object.
	 * 
	 * @param domain
	 *            a SCOP domain
	 * @param scopDatabase
	 *            A {@link ScopDatabase} to use
	 * @param strictLigandHandling
	 *            If set to false, hetero-atoms are included if and only if they belong to a chain to which the SCOP
	 *            domain belongs; if set to true, hetero-atoms are included if and only if they are strictly within the
	 *            definition (residue numbers) of the SCOP domain
	 * @return a Structure object
	 * @throws IOException
	 * @throws StructureException
	 */
	public Structure getStructureForDomain(final ScopDomain domain, final ScopDatabase scopDatabase,
			final boolean strictLigandHandling) throws IOException, StructureException {

		final String pdbId = domain.getPdbId();
		final Structure fullStructure = getStructure(pdbId);

		// build the substructure
		final StringBuilder rangeString = new StringBuilder();
		final Iterator<String> iter = domain.getRanges().iterator();
		while (iter.hasNext()) {
			rangeString.append(iter.next());
			if (iter.hasNext()) {
				rangeString.append(",");
			}
		}
		final Structure structure = StructureTools.getSubRanges(fullStructure, rangeString.toString());
		structure.setName(domain.getScopId());
		structure.setPDBCode(domain.getScopId());

		// because ligands sometimes occur after TER records in PDB files, we may need to add some ligands back in
		// specifically, we add a ligand if and only if it occurs within the domain
		AtomPositionMap map = null;
		List<ResidueRange> rrs = null;
		if (strictLigandHandling) {
			map = new AtomPositionMap(StructureTools.getAllAtomArray(fullStructure), AtomPositionMap.ANYTHING_MATCHER);
			rrs = ResidueRange.parseMultiple(domain.getRanges(), map);
		}
		for (final Chain chain : fullStructure.getChains()) {
			if (!structure.hasChain(chain.getChainID())) {
				continue; // we can't do anything with a chain our domain
			}
			// doesn't contain
			final Chain newChain = structure.getChainByPDB(chain.getChainID());
			final List<Group> ligands = StructureTools.filterLigands(chain.getAtomGroups());
			for (final Group group : ligands) {
				boolean shouldContain = true;
				if (strictLigandHandling) {
					shouldContain = false; // whether the ligand occurs within the domain
					for (final ResidueRange rr : rrs) {
						if (rr.contains(group.getResidueNumber(), map)) {
							shouldContain = true;
						}
					}
				}
				final boolean alreadyContains = newChain.getAtomGroups().contains(group); // we don't want to add
																							// duplicate
				// ligands
				if (shouldContain && !alreadyContains) {
					newChain.addGroup(group);
				}
			}
		}

		// build a more meaningful description for the new structure
		final StringBuilder header = new StringBuilder();
		header.append(domain.getClassificationId());
		if (scopDatabase != null) {
			final int sf = domain.getSuperfamilyId();
			final ScopDescription description = scopDatabase.getScopDescriptionBySunid(sf);
			if (description != null) {
				header.append(" | ");
				header.append(description.getDescription());
			}
		}
		structure.getPDBHeader().setDescription(header.toString());

		return structure;

	}

	/**
	 * Returns the representation of a {@link ScopDomain} as a BioJava {@link Structure} object.
	 * 
	 * @param scopId
	 *            a SCOP Id
	 * @return a Structure object
	 * @throws IOException
	 * @throws StructureException
	 */
	public Structure getStructureForDomain(final String scopId) throws IOException, StructureException {
		if (scopInstallation == null) {
			scopInstallation = ScopFactory.getSCOP();
		}
		return getStructureForDomain(scopId, scopInstallation);
	}

	/**
	 * Returns the representation of a {@link ScopDomain} as a BioJava {@link Structure} object.
	 * 
	 * @param scopId
	 *            a SCOP Id
	 * @param scopDatabase
	 *            A {@link ScopDatabase} to use
	 * @return a Structure object
	 * @throws IOException
	 * @throws StructureException
	 */
	public Structure getStructureForDomain(final String scopId, final ScopDatabase scopDatabase) throws IOException,
			StructureException {
		final ScopDomain domain = scopDatabase.getDomainByScopID(scopId);
		return getStructureForDomain(domain, scopDatabase);
	}

	/**
	 * Does the cache automatically download files that are missing from the local installation from the PDB FTP site?
	 * 
	 * @return flag
	 */
	public boolean isAutoFetch() {
		return autoFetch;
	}

	/**
	 * <b>N.B.</b> This feature won't work unless the structure wasn't found & autoFetch is set to <code>true</code>.
	 * 
	 * @return the fetchCurrent
	 */
	public boolean isFetchCurrent() {
		return fetchCurrent;
	}

	/**
	 * forces the cache to fetch the file if its status is OBSOLETE. This feature has a higher priority than
	 * {@link #setFetchCurrent(boolean)}.<br>
	 * <b>N.B.</b> This feature won't work unless the structure wasn't found & autoFetch is set to <code>true</code>.
	 * 
	 * @return the fetchFileEvenIfObsolete
	 * @author Amr AL-Hossary
	 * @see #fetchCurrent
	 * @since 3.0.2
	 */
	public boolean isFetchFileEvenIfObsolete() {
		return fetchFileEvenIfObsolete;
	}

	/**
	 * Is the organization of files within the directory split, as on the PDB FTP servers, or are all files contained in
	 * one directory.
	 * 
	 * @return flag
	 */
	public boolean isSplit() {
		return isSplit;
	}

	/**
	 * Reports whether strict scop naming will be enforced, or whether this AtomCache should try to guess some simple
	 * variants on scop domains.
	 * 
	 * @return true if scop names should be used strictly with no guessing
	 */
	public boolean isStrictSCOP() {
		return strictSCOP;
	}

	/**
	 * Send a signal to the cache that the system is shutting down. Notifies underlying SerializableCache instances to
	 * flush themselves...
	 */
	public void notifyShutdown() {
		// System.out.println(" AtomCache got notify shutdown..");
		if (pdpprovider != null) {
			if (pdpprovider instanceof RemotePDPProvider) {
				final RemotePDPProvider remotePDP = (RemotePDPProvider) pdpprovider;
				remotePDP.flushCache();
			}
		}

		// todo: use a SCOP implementation that is backed by SerializableCache
		if (scopInstallation != null) {
			if (scopInstallation instanceof CachedRemoteScopInstallation) {
				final CachedRemoteScopInstallation cacheScop = (CachedRemoteScopInstallation) scopInstallation;
				cacheScop.flushCache();
			}
		}

	}

	/**
	 * Does the cache automatically download files that are missing from the local installation from the PDB FTP site?
	 * 
	 * @param autoFetch
	 *            flag
	 */
	public void setAutoFetch(final boolean autoFetch) {
		this.autoFetch = autoFetch;
	}

	/**
	 * set the location at which utility data should be cached.
	 * 
	 * @param cachePath
	 */
	public void setCachePath(final String cachePath) {
		this.cachePath = cachePath;
		System.setProperty(AbstractUserArgumentProcessor.CACHE_DIR, cachePath);

	}

	/**
	 * if enabled, the reader searches for the newest possible PDB ID, if not present in he local installation. The
	 * {@link #setFetchFileEvenIfObsolete(boolean)} function has a higher priority than this function.<br>
	 * <b>N.B.</b> This feature won't work unless the structure wasn't found & autoFetch is set to <code>true</code>.
	 * 
	 * @param fetchCurrent
	 *            the fetchCurrent to set
	 * @author Amr AL-Hossary
	 * @see #setFetchFileEvenIfObsolete(boolean)
	 * @since 3.0.2
	 */
	public void setFetchCurrent(final boolean fetchNewestCurrent) {
		fetchCurrent = fetchNewestCurrent;
	}

	/**
	 * <b>N.B.</b> This feature won't work unless the structure wasn't found & autoFetch is set to <code>true</code>.
	 * 
	 * @param fetchFileEvenIfObsolete
	 *            the fetchFileEvenIfObsolete to set
	 */
	public void setFetchFileEvenIfObsolete(final boolean fetchFileEvenIfObsolete) {
		this.fetchFileEvenIfObsolete = fetchFileEvenIfObsolete;
	}

	public void setFileParsingParams(final FileParsingParameters params) {
		this.params = params;
	}

	/**
	 * Set the path that is used to cache PDB files.
	 * 
	 * @param path
	 *            to a directory
	 */
	public void setPath(final String path) {
		System.setProperty(AbstractUserArgumentProcessor.PDB_DIR, path);
		this.path = path;
	}

	public void setPdpprovider(final PDPProvider pdpprovider) {
		this.pdpprovider = pdpprovider;
	}

	/**
	 * Is the organization of files within the directory split, as on the PDB FTP servers, or are all files contained in
	 * one directory.
	 * 
	 * @param isSplit
	 *            flag
	 */
	public void setSplit(final boolean isSplit) {
		this.isSplit = isSplit;
	}

	/**
	 * When strictSCOP is enabled, SCOP domain identifiers (eg 'd1gbga_') are matched literally to the SCOP database.
	 * 
	 * When disabled, some simple mistakes are corrected automatically. For instance, the invalid identifier 'd1gbg__'
	 * would be corrected to 'd1gbga_' automatically.
	 * 
	 * @param strictSCOP
	 *            Indicates whether strict scop names should be used.
	 */
	public void setStrictSCOP(final boolean strictSCOP) {
		this.strictSCOP = strictSCOP;
	}

	private boolean checkLoading(final String name) {
		return currentlyLoading.contains(name);

	}

	private Structure getBioAssembly(final String name) throws IOException, StructureException {

		// can be specified as:
		// BIO:1fah - first one
		// BIO:1fah:0 - asym unit
		// BIO:1fah:1 - first one
		// BIO:1fah:2 - second one

		final String pdbId = name.substring(4, 8);
		int biolNr = 1;
		if (name.length() > 8) {
			biolNr = Integer.parseInt(name.substring(9, name.length()));
		}

		return StructureIO.getBiologicalAssembly(pdbId, biolNr);

	}

	private Structure getPDPStructure(final String pdpDomainName) {

		// System.out.println("loading PDP domain from server " + pdpDomainName);
		if (pdpprovider == null) {
			pdpprovider = new RemotePDPProvider(true);
		}

		return pdpprovider.getDomain(pdpDomainName, this);

	}

	private ScopDomain getScopDomain(final String scopId) {

		if (scopInstallation == null) {
			scopInstallation = ScopFactory.getSCOP();
		}

		return scopInstallation.getDomainByScopID(scopId);
	}

	private Structure getStructureFromCATHDomain(final StructureName structureName) throws IOException,
			StructureException {

		final CathInstallation cathInstall = new CathInstallation(path);

		final CathDomain cathDomain = cathInstall.getDomainByCathId(structureName.getName());

		final List<CathSegment> segments = cathDomain.getSegments();

		final StringWriter range = new StringWriter();

		int rangePos = 0;
		final String chainId = structureName.getChainId();
		for (final CathSegment segment : segments) {
			rangePos++;

			range.append(chainId);
			range.append("_");

			range.append(segment.getStart());
			range.append("-");
			range.append(segment.getStop());
			if (segments.size() > 1 && rangePos < segments.size()) {
				range.append(",");
			}
		}

		final String pdbId = structureName.getPdbId();

		Structure s = null;

		try {
			s = getStructure(pdbId);

		} catch (final StructureException ex) {
			System.err.println("error getting Structure for " + pdbId);

			throw new StructureException(ex);
		}

		final String rangeS = range.toString();
		System.out.println(rangeS);
		final Structure n = StructureTools.getSubRanges(s, rangeS);

		// add the ligands of the chain...

		final Chain newChain = n.getChainByPDB(structureName.getChainId());
		final Chain origChain = s.getChainByPDB(structureName.getChainId());
		final List<Group> ligands = origChain.getAtomLigands();

		for (final Group g : ligands) {
			if (!newChain.getAtomGroups().contains(g)) {
				newChain.addGroup(g);
			}
		}

		// set new Header..
		n.setName(structureName.getName());
		n.setPDBCode(structureName.getPdbId());

		n.getPDBHeader().setDescription(cathDomain.getDomainName());

		return n;
	}

	private Structure getStructureFromSCOPDomain(final String name) throws IOException, StructureException {
		// looks like a SCOP domain!
		ScopDomain domain;
		if (strictSCOP) {
			domain = getScopDomain(name);
		} else {
			domain = guessScopDomain(name);
		}

		System.out.println(domain);
		if (domain != null) {
			final Structure s = getStructureForDomain(domain);
			return s;
		}

		// Guessing didn't work, so just use the PDBID and Chain from name
		if (!strictSCOP) {
			final Matcher scopMatch = scopIDregex.matcher(name);
			if (scopMatch.matches()) {
				String pdbID = scopMatch.group(1);
				final String chainID = scopMatch.group(2);

				// None of the actual SCOP domains match. Guess that '_' means 'whole chain'
				if (!chainID.equals("_")) {
					// Add chain identifier
					pdbID += "." + scopMatch.group(2);
				}
				// Fetch the structure by pdb id
				final Structure struct = getStructure(pdbID);
				if (struct != null) {
					System.err.println("Trying chain " + pdbID);
				}

				return struct;
			}
		}

		throw new StructureException("Unable to get structure for SCOP domain: " + name);
	}

	private Structure getStructureFromURL(URL url) throws IOException, StructureException {
		// looks like a URL for a file was provided:
		System.out.println("fetching structure from URL:" + url);

		final String queryS = url.getQuery();

		String chainId = null;
		if (queryS != null && queryS.startsWith("chainId=")) {
			chainId = queryS.substring(8);

			final String fullu = url.toString();

			if (fullu.startsWith("file:") && fullu.endsWith("?" + queryS)) {
				// for windowze, drop the query part from the URL again
				// otherwise there will be a "file not found error" ...

				final String newu = fullu.substring(0, fullu.length() - ("?" + queryS).length());
				// System.out.println(newu);
				url = new URL(newu);
			}
		}

		final PDBFileReader reader = new PDBFileReader();
		reader.setPath(path);
		reader.setPdbDirectorySplit(isSplit);
		reader.setAutoFetch(autoFetch);
		reader.setFetchFileEvenIfObsolete(fetchFileEvenIfObsolete);
		reader.setFetchCurrent(fetchCurrent);

		reader.setFileParsingParameters(params);

		final Structure s = reader.getStructure(url);
		if (chainId == null) {
			return StructureTools.getReducedStructure(s, -1);
		} else {
			return StructureTools.getReducedStructure(s, chainId);
		}
	}

	/**
	 * <p>
	 * Guess a scop domain. If an exact match is found, return that.
	 * 
	 * <p>
	 * Otherwise, return the first scop domain found for the specified protein such that
	 * <ul>
	 * <li>The chains match, or one of the chains is '_' or '.'.
	 * <li>The domains match, or one of the domains is '_'.
	 * </ul>
	 * 
	 * 
	 * @param name
	 * @return
	 * @throws IOException
	 * @throws StructureException
	 */
	private ScopDomain guessScopDomain(final String name) throws IOException, StructureException {
		final List<ScopDomain> matches = new LinkedList<ScopDomain>();

		// Try exact match first
		final ScopDomain domain = getScopDomain(name);
		if (domain != null) {
			return domain;
		}

		// Didn't work. Guess it!
		System.err.println("Warning, could not find SCOP domain: " + name);

		final Matcher scopMatch = scopIDregex.matcher(name);
		if (scopMatch.matches()) {
			final String pdbID = scopMatch.group(1);
			final String chainID = scopMatch.group(2);
			final String domainID = scopMatch.group(3);

			if (scopInstallation == null) {
				scopInstallation = ScopFactory.getSCOP();
			}

			for (final ScopDomain potentialSCOP : scopInstallation.getDomainsForPDB(pdbID)) {
				final Matcher potMatch = scopIDregex.matcher(potentialSCOP.getScopId());
				if (potMatch.matches()) {
					if (chainID.equals(potMatch.group(2)) || chainID.equals("_") || chainID.equals(".")
							|| potMatch.group(2).equals("_") || potMatch.group(2).equals(".")) {
						if (domainID.equals(potMatch.group(3)) || domainID.equals("_") || potMatch.group(3).equals("_")) {
							// Match, or near match
							matches.add(potentialSCOP);
						}
					}
				}
			}
		}

		final Iterator<ScopDomain> match = matches.iterator();
		if (match.hasNext()) {
			final ScopDomain bestMatch = match.next();
			System.err.print("Trying domain " + bestMatch.getScopId() + ".");
			if (match.hasNext()) {
				System.err.print(" Other possibilities: ");
				while (match.hasNext()) {
					System.err.print(match.next().getScopId() + " ");
				}
			}
			System.err.println();
			return bestMatch;
		} else {
			return null;
		}
	}

	protected void flagLoading(final String name) {
		if (!currentlyLoading.contains(name)) {
			currentlyLoading.add(name);
		}
	}

	protected void flagLoadingFinished(final String name) {
		currentlyLoading.remove(name);
	}

	protected Structure loadStructureFromByPdbId(final String pdbId) throws StructureException {

		Structure s;
		flagLoading(pdbId);
		try {
			final PDBFileReader reader = new PDBFileReader();
			reader.setPath(path);
			reader.setPdbDirectorySplit(isSplit);
			reader.setAutoFetch(autoFetch);
			reader.setFetchFileEvenIfObsolete(fetchFileEvenIfObsolete);
			reader.setFetchCurrent(fetchCurrent);

			reader.setFileParsingParameters(params);

			s = reader.getStructureById(pdbId.toLowerCase());

		} catch (final Exception e) {
			flagLoadingFinished(pdbId);
			throw new StructureException(e.getMessage() + " while parsing " + pdbId, e);
		}
		flagLoadingFinished(pdbId);

		return s;
	}

}
