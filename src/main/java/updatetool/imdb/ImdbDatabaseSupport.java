package updatetool.imdb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.sqlite.SQLiteException;
import org.tinylog.Logger;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import updatetool.Globals;
import updatetool.Main;
import updatetool.common.DatabaseSupport.LibraryType;
import updatetool.common.KeyValueStore;
import updatetool.common.SqliteDatabaseProvider;
import updatetool.common.Utility;
import updatetool.imdb.ImdbPipeline.ImdbPipelineConfiguration;

public class ImdbDatabaseSupport {
    private final SqliteDatabaseProvider provider;
    private final KeyValueStore newAgentMapping;
    private final ImdbPipelineConfiguration config;
    
    public ImdbDatabaseSupport(SqliteDatabaseProvider provider, KeyValueStore newAgentMapping, ImdbPipelineConfiguration config) {
        this.provider = provider;
        this.newAgentMapping = newAgentMapping;
        this.config = config;
        
        if(config.executeUpdatesOverPlexSqliteBinary()) {
            testPlexSqliteBinaryVersion();
        }
    }
    
    @SuppressFBWarnings("DM_EXIT")
    private void testPlexSqliteBinaryVersion() {
        Path p = Paths.get(config.executeUpdatesOverPlexSqliteVersion);
        if(Files.notExists(p) || Files.isDirectory(p)) {
            Logger.error("Plex Sqlite3 binary has not been supplied under: {}", config.executeUpdatesOverPlexSqliteVersion);
            Logger.error("Either supply a correct path or run the application without the environment variable 'USE_PLEX_SQLITE_BINARY_FOR_WRITE_ACCESS'.");
            Logger.error("Application is shutting down NOW due to invalid configuration...");
            System.exit(-1);
        }
        
        try {
            var proc = new ProcessBuilder(config.executeUpdatesOverPlexSqliteVersion.trim()).redirectErrorStream(true).start();
            OutputStreamWriter br = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
            br.write("select sqlite_version(); PRAGMA compile_options;");
            br.close();

            StringBuilder sb = new StringBuilder();
            Main.EXECUTOR.submit(() -> {
                String line;
                InputStreamReader isr = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8);
                try(BufferedReader rdr = new BufferedReader(isr)) {
                    while ((line = rdr.readLine()) != null) {
                        sb.append(line).append(" | ");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            

            var result = proc.waitFor(1, TimeUnit.SECONDS);
            if(!result) {
                Logger.error("Checking Plex SQLite version took more than 1s... Likely invalid binary supplied @ {}. Exiting tool...", config.executeUpdatesOverPlexSqliteVersion);
                System.exit(-1);
            } else {
                if(!sb.toString().startsWith("3.")) {
                     Logger.error("INVALID PLEX SQLITE BINARY SUPPLIED @ {} -> RETURNED {} != 3.x | APPLICATION WILL SHUTDOWN", config.executeUpdatesOverPlexSqliteVersion, sb.toString());
                     System.exit(-1);
                } else {
                    if(!sb.toString().contains("ENABLE_ICU")) {
                        Logger.error("INVALID PLEX SQLITE BINARY SUPPLIED @ {} -> No match 'ENABLE_ICU' in loaded extensions. | APPLICATION WILL SHUTDOWN", config.executeUpdatesOverPlexSqliteVersion);
                        System.exit(-1);
                    }
                    Logger.info("Plex SQLite binary version: {}", sb.toString());
                }
            }
        } catch(Exception e) {
            throw Utility.rethrow(e);
        }
    }

    public static class ImdbMetadataResult {
      //Id will be resolved in the pipeline and not here
        public String imdbId, extractedId;
        public String title, hash;
        public Integer id, libraryId;
        public String extraData, guid;
        public Double rating, audienceRating;
        public boolean resolved;
        public LibraryType type;
        public boolean hasEpisodeAgentFlag;
        
        public ImdbMetadataResult() {};
        
        private ImdbMetadataResult(ResultSet rs, LibraryType type) throws SQLException {
            this.type = type;
            id = rs.getInt(1);
            libraryId = rs.getInt(2);
            guid = rs.getString(3);
            title = rs.getString(4);
            extraData = rs.getString(5);
            hash = rs.getString(6);
            rating = (Double) rs.getObject(7);
            audienceRating = (Double) rs.getObject(8);
            hasEpisodeAgentFlag = guid.startsWith("plex://episode");
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(imdbId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ImdbMetadataResult other = (ImdbMetadataResult) obj;
            return Objects.equals(id, other.id);
        }

        @Override
        public String toString() {
            return "ImdbMetadataResult [imdbId=" + imdbId + ", extractedId=" + extractedId + ", title=" + title
                    + ", hash=" + hash + ", id=" + id + ", libraryId=" + libraryId + ", extraData=" + extraData
                    + ", guid=" + guid + ", rating=" + rating + ", audienceRating=" + audienceRating + ", resolved="
                    + resolved + ", type=" + type + "]";
        }
    }

    public List<ImdbMetadataResult> requestEntries(long libraryId, LibraryType type) {
        return requestMetadata("SELECT id, library_section_id, guid, title, extra_data, hash, rating, audience_rating from metadata_items WHERE media_item_count = 1 AND library_section_id = " + libraryId, type);
    }

    public List<ImdbMetadataResult> requestTvSeriesRoot(long libraryId) {
        return requestMetadata("SELECT id, library_section_id, guid, title, extra_data, hash, rating, audience_rating from metadata_items WHERE media_item_count = 0 AND parent_id IS NULL AND library_section_id = " + libraryId, LibraryType.SERIES);
    }
    
    public List<ImdbMetadataResult> requestTvSeasonRoot(long libraryId) {
        return requestMetadata("SELECT id, library_section_id, guid, title, extra_data, hash, rating, audience_rating from metadata_items WHERE media_item_count = 0 AND parent_id NOT NULL AND library_section_id = " + libraryId, LibraryType.SERIES);
    }
    
    private List<ImdbMetadataResult> requestMetadata(String query, LibraryType type) {        
        try(var handle = provider.queryFor(query)){
            List<ImdbMetadataResult> list = new ArrayList<>();
            while(handle.result().next()) {
                var m = new ImdbMetadataResult(handle.result(), type);
                updateNewAgentMetadataMapping(m);
                list.add(m);
            }
            return list;
        } catch (SQLException e) {
            throw Utility.rethrow(e);
        }
    }

    private void updateNewAgentMetadataMapping(ImdbMetadataResult m) throws SQLException {
        if(newAgentMapping == null)
            return;
        
        if(!Globals.isNewAgent(m))
            return;
        
        String v = newAgentMapping.lookup(m.guid);
        if(v != null && v.startsWith("imdb://"))
            return;
        
        String result = null;
        try(var handle = provider.queryFor("SELECT t.tag FROM taggings tg LEFT JOIN tags t ON tg.tag_id = t.id AND t.tag_type = 314 WHERE tg.metadata_item_id = " + m.id + " AND t.tag NOT NULL")) {
            while(handle.result().next()) {
                String id = handle.result().getString(1);
                if(result == null || !result.startsWith("imdb://"))
                    result = id;
            }
        }
                
        if(result != null) {
            if(newAgentMapping.cache(m.guid, result)) {
                Logger.info("Associated and cached {} with new movie agent guid {} ({}).", result, m.guid, m.title);
            }
        } else {
            Logger.warn("No external metadata provider id associated with this guid {} ({}). This item will not be processed any further.", m.guid, m.title);
        }
    }

    public void requestBatchUpdateOf(List<ImdbMetadataResult> items) throws SQLiteException {
        if(items.size() == 0)
            return;
        
        List<ImdbMetadataResult> newAgent = new ArrayList<>(),
                                 oldAgent = new ArrayList<>();
        
        items.forEach(i -> {
            if(Globals.isNewAgent(i)) {
                newAgent.add(i);
            } else {
                oldAgent.add(i);
            }
        });
        
        if(!newAgent.isEmpty()) {
            Logger.info("Running batch update for {} items with new plex agent.", newAgent.size());
            internalBatchUpdate(newAgent, true);
        }
        
        if(!oldAgent.isEmpty()) {
            Logger.info("Running batch update for {} item(s) with old plex agent.", oldAgent.size());
            internalBatchUpdate(oldAgent, false);
        }
    }
    
    private String sanitize(String s) {
        return s.replace("'", "\"");
    }
    
    @SuppressFBWarnings("DM_EXIT")
    private void internalBatchUpdateOverPlexSqliteBinary(List<ImdbMetadataResult> items, boolean isNewAgent, String plexSqliteBinaryPath) {
        StringBuilder cb = new StringBuilder(1000);
        cb.append("BEGIN TRANSACTION;\n");
        
        for(var item : items) {
            Double d = isNewAgent ? item.audienceRating : item.rating;
            
            //TODO: hotfix, investigate further only happened to one person over the entire tool lifetime
            if(d == null) {
                Logger.error("Null value encountered. Should not be possible. Skipping entry to not crash tool. Contact maintainer with this dump: " + Objects.toString(item));
                continue;
            }
            
            String query = isNewAgent ? String.format(
                    "UPDATE metadata_items SET audience_rating = %s, extra_data = '%s', rating = NULL WHERE id = %s;%n", d, sanitize(item.extraData), item.id) : String.format(
                    "UPDATE metadata_items SET rating = %s, extra_data = '%s' WHERE id = %s;%n", d, sanitize(item.extraData), item.id);
            cb.append(query);
        }
        cb.append("COMMIT TRANSACTION;");
        
        try {
            var proc = new ProcessBuilder(config.executeUpdatesOverPlexSqliteVersion.trim(), config.dbLocation).redirectErrorStream(true).start();
            OutputStreamWriter br = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
            br.write(cb.toString());
            br.close();

            StringBuilder sb2 = new StringBuilder();
            Main.EXECUTOR.submit(() -> {
                String line;
                InputStreamReader isr = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8);
                try(BufferedReader rdr = new BufferedReader(isr)) {
                    while ((line = rdr.readLine()) != null) {
                        Logger.info(line);
                        sb2.append(line).append(" | ");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            
            var result = proc.waitFor(30, TimeUnit.SECONDS);
            if(!result) {
                Logger.error("Executing updates for {} item(s) took longer than 30s... Something bad happened... @ {}. Exiting tool...", config.executeUpdatesOverPlexSqliteVersion);
                Logger.error("Dumping SQlite 3 STD::OUT");
                Logger.error("===================");
                Logger.error(sb2.toString());
                Logger.error("===================");
                System.exit(-1);
            } else {
                var str = sb2.toString().trim();
                if(str.isBlank()) {
                    Logger.info("Update executed successfully via Plex SQLite binary call.");
                } else {
                    Logger.warn("Update via Plex SQLite binary call produced potential error messages. Please contact the author of the tool with this output:");
                    Logger.warn(str);
                    Logger.warn("==================================================");
                }
            }
        } catch(Exception e) {
            throw Utility.rethrow(e);
        }
    }
    
    @SuppressFBWarnings({"DM_EXIT", "REC_CATCH_EXCEPTION", "OBL_UNSATISFIED_OBLIGATION", "DE_MIGHT_IGNORE"})
    private void internalBatchUpdate(List<ImdbMetadataResult> items, boolean isNewAgent) {
        
        if(config.executeUpdatesOverPlexSqliteBinary()) {
            internalBatchUpdateOverPlexSqliteBinary(items, isNewAgent, config.executeUpdatesOverPlexSqliteVersion);
            return;
        }
        
        boolean success = true;
        boolean mitigationIcuNeeded, mitigationIcuTriggersDisabled = false;
        List<AutoCloseable> close = new ArrayList<>();
        Map<String, String> disabledTriggers = new LinkedHashMap<>();
        
        try {
            
            // Mitigation
            var mitigationIcu = provider.connection.createStatement();
            close.add(mitigationIcu);
            var rs = mitigationIcu.executeQuery("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND sql LIKE \"%ON metadata_items%\";");
            Map<String, String> storedSql = new HashMap<>();
            while(rs.next()) {
                storedSql.put(rs.getString(1), rs.getString(2));
            }
            rs.close();
            
            mitigationIcuNeeded = storedSql.size() > 0;
            Logger.info("PlexDB ICU Mitigation enabled: {}", mitigationIcuNeeded);
            
            if(mitigationIcuNeeded) {
                Logger.info("=== DATABASE CORRUPTION MITIGATION ===");
                Logger.info("Disabling the following metadata_items triggers temporarily:");
                
                for(var s : storedSql.entrySet()) {
                    Logger.info("{} => {}", s.getKey(), s.getValue());
                    var stmt = provider.connection.createStatement();
                    close.add(stmt);
                    stmt.executeUpdate(String.format("DROP TRIGGER %s;", s.getKey()));
                    disabledTriggers.put(s.getKey(), s.getValue());
                    Logger.info("Disabled Trigger: {}", s.getKey());
                }
                
                mitigationIcuTriggersDisabled = true;
                Logger.info("=== ALL metadata_items TRIGGERS DISABLED :: PROCEED UPDATE ===");
            }
            
            try(var s = provider.connection.prepareStatement(isNewAgent ? "UPDATE metadata_items SET audience_rating = ?, extra_data = ?, rating = NULL WHERE id = ?" 
                    : "UPDATE metadata_items SET rating = ?, extra_data = ? WHERE id = ?")) {
                for(var item : items) {
                    Double d = isNewAgent ? item.audienceRating : item.rating;
                    
                    //TODO: hotfix, investigate further only happened to one person over the entire tool lifetime
                    if(d == null) {
                        Logger.error("Null value encountered. Should not be possible. Skipping entry to not crash tool. Contact maintainer with this dump: " + Objects.toString(item));
                        continue;
                    }
                    
                    s.setDouble(1, d);
                    s.setString(2, item.extraData);
                    s.setInt(3, item.id);
                    s.addBatch();
                }
                int[] records = s.executeBatch();
                for(int c : records) {
                    if (c == Statement.EXECUTE_FAILED) {
                        Logger.error("Batch Update failed: " + c + " | All: " + Arrays.toString(records));
                        success = false;
                        break;
                    }
                }
            } catch (SQLException e) {
                success = false;
                throw Utility.rethrow(e);
            } finally {
                try {
                    if(success) {
                        provider.connection.commit();
                    } else {
                        provider.connection.rollback();
                    }
                } catch(SQLException e) {
                    throw Utility.rethrow(e);
                }
            }
        } catch(Exception e) {
            throw Utility.rethrow(e);
        } finally {
            for(AutoCloseable a : close) {
                try {
                    a.close();
                } catch (Exception e) {}
            }
            close.clear();
            
            if(mitigationIcuTriggersDisabled) {
                Map<String, String> uncompleted = new LinkedHashMap<>(disabledTriggers);
                try {
                    Logger.info("=== RESTORING metadata_items TRIGGERS AFTER UPDATE ===");
                    for(var s : disabledTriggers.entrySet()) {
                        var stmt = provider.connection.createStatement();
                        close.add(stmt);
                        stmt.executeUpdate(s.getValue());
                        Logger.info("Restored Trigger: {}", s.getKey());
                        uncompleted.remove(s.getKey());
                    }
                    Logger.info("=== DATABASE CORRUPTION MITIGATION COMPLETED :: TRIGGERS RESTORED ===");
                } catch (SQLException ex) {
                    
                    if(uncompleted.isEmpty()) {
                        Logger.error(ex);
                        Logger.error("Unknown error encountered. Please contact the author of this tool.");
                        System.exit(-1);
                    }
                    
                    Logger.error(ex);
                    Logger.error("======================================");
                    Logger.error("WARNING!!! COULD NOT RESTORE DISABLED TRIGGERS IN ICU MITIGATION!!!");
                    Logger.error("RUN THE TRIGGER CREATION QUERIES BELOW ON YOUR PLEX DATABASE MANUALLY TO RESTORE THE DISABLED TRIGGERS!");
                    Logger.error("======================================");
                    for(var s : uncompleted.entrySet()) {
                        Logger.error(s.getValue());
                    }
                    Logger.error("======================================");
                    Logger.error("TOOL WILL EXIT NOW! DON'T USE BEFORE HAVING EXECUTED THESE COMMANDS!");
                    Logger.error("CONTACT THE AUTHOR OF THIS TOOL IF YOU DON'T KNOW WHAT TO DO NOW!");
                    System.exit(-1);
                } finally {
                    for(AutoCloseable a : close) {
                        try {
                            a.close();
                        } catch (Exception e) {}
                    }
                }
            }
        }
    }

}