package barqsoft.footballscores.service;

import android.app.IntentService;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.Vector;

import barqsoft.footballscores.MainActivity;
import barqsoft.footballscores.db.DatabaseContract;
import barqsoft.footballscores.R;
import barqsoft.footballscores.rest.FootbalDataClient;
import barqsoft.footballscores.rest.model.League;
import barqsoft.footballscores.rest.model.Match;
import barqsoft.footballscores.rest.model.MatchResult;
import barqsoft.footballscores.rest.model.Team;

/**
 * Created by yehya khaled on 3/2/2015.
 */
public class ScoresFetchService extends IntentService
{
    public static final String TAG = ScoresFetchService.class.getSimpleName();
    public ScoresFetchService()
    {
        super(ScoresFetchService.class.getSimpleName());
    }

    @Override
    protected void onHandleIntent(Intent intent){
        getMatches("n2");
        getMatches("p2");
        Intent messageIntent = new Intent(MainActivity.UPDATE_SCORES);
        LocalBroadcastManager.getInstance(this.getApplicationContext()).sendBroadcast(messageIntent);
    }

    private void getMatches(String timeFrame){
        FootbalDataClient fdc = new FootbalDataClient(getString(R.string.api_key));
        try {
            List<Match> matches = fdc.listMatches(timeFrame);
            if (matches==null || matches.size()==0) return;
            Log.i(TAG, "Fetched "+matches.size()+" records");
            ContentValues[] vals = new ContentValues[matches.size()];
            for(int i=0;i<matches.size();i++){
                Match m = matches.get(i);
                ContentValues v = new ContentValues();
                v.put(DatabaseContract.ScoresEntry.MATCH_ID, ContentUris.parseId(Uri.parse(m.getLinks().getSelf())));
                v.put(DatabaseContract.ScoresEntry.MATCH_DAY, m.getMatchday());
                v.put(DatabaseContract.ScoresEntry.HOME_COL, m.getHomeTeamName());
                v.put(DatabaseContract.ScoresEntry.AWAY_COL, m.getAwayTeamName());

                MatchResult r = m.getMatchResult();
                if (r!=null){
                    String h = (r.getGoalsHomeTeam()!=null)?String.valueOf(r.getGoalsHomeTeam()):"?";
                    String a = (r.getGoalsAwayTeam()!=null)?String.valueOf(r.getGoalsAwayTeam()):"?";
                    v.put(DatabaseContract.ScoresEntry.HOME_GOALS_COL, h);
                    v.put(DatabaseContract.ScoresEntry.AWAY_GOALS_COL, a);
                }
                long leagueId = ContentUris.parseId(Uri.parse(m.getLinks().getSoccerSeason()));
                getLeagueInfo(leagueId,v);

                long homeTeamId = ContentUris.parseId(Uri.parse(m.getLinks().getHomeTeam()));
                getTeamInfo(homeTeamId, DatabaseContract.ScoresEntry.HOME_CREST, v);

                long awayTeamId = ContentUris.parseId(Uri.parse(m.getLinks().getAwayTeam()));
                getTeamInfo(awayTeamId, DatabaseContract.ScoresEntry.AWAY_CREST, v);

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = sdf.parse(m.getDate());

                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                dateFormat.setTimeZone(TimeZone.getDefault());
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
                timeFormat.setTimeZone(TimeZone.getDefault());
                v.put(DatabaseContract.ScoresEntry.DATE_COL, dateFormat.format(date));
                v.put(DatabaseContract.ScoresEntry.TIME_COL, timeFormat.format(date));
                vals[i]=v;
            }
            int insCnt = getContentResolver().bulkInsert(DatabaseContract.ScoresEntry.CONTENT_URI,vals);
            Log.i(TAG, "Inserted "+insCnt+" records");
        } catch (IOException | ParseException e) {
            Log.e(TAG,e.getMessage(),e);
        }
    }

    void getTeamInfo(long teamId, String column, ContentValues v) throws IOException {
        Cursor c = getContentResolver().query(DatabaseContract.TeamEntry.buildTeamWithId(teamId),null,null,null,null);
        if (c==null || !c.moveToFirst()){
            FootbalDataClient fdc = new FootbalDataClient(getString(R.string.api_key));
            Team team = fdc.getTeam(String.valueOf(teamId));
            ContentValues values = new ContentValues();
            values.put(DatabaseContract.TeamEntry._ID,teamId);
            values.put(DatabaseContract.TeamEntry.NAME_COL, team.getName());
            values.put(DatabaseContract.TeamEntry.SHORT_NAME_COL, team.getShortName());
            values.put(DatabaseContract.TeamEntry.CREST_URL_COL, team.getCrestUrl());
            Uri newItemUri = getContentResolver().insert(DatabaseContract.TeamEntry.CONTENT_URI,values);
            c = getContentResolver().query(newItemUri,null,null,null,null);
        }

        if (c.moveToFirst()) {
            String crestUrl = c.getString(c.getColumnIndex(DatabaseContract.TeamEntry.CREST_URL_COL));
            v.put(column,crestUrl);
            Log.i(TAG,column+": "+crestUrl);
        }else{
            Log.e(TAG, "Empty response for "+column);
        }
        c.close();
    }

    void getLeagueInfo(long leagueId, ContentValues v) throws IOException {
        Cursor c = getContentResolver().query(
                DatabaseContract.LeagueEntry.buildLeagueWithId(leagueId),
                null, null, null, null
        );
        if (c==null || !c.moveToFirst()){
            FootbalDataClient fdc = new FootbalDataClient(getString(R.string.api_key));
            League league = fdc.getLeague(String.valueOf(leagueId));
            if (league!=null){
                ContentValues values = new ContentValues();
                values.put(DatabaseContract.LeagueEntry._ID, leagueId);
                values.put(DatabaseContract.LeagueEntry.NAME_COL, league.getCaption());
                values.put(DatabaseContract.LeagueEntry.SHORT_NAME_COL, league.getLeague());
                values.put(DatabaseContract.LeagueEntry.YEAR_COL, league.getYear());
                Uri newItemUri = getContentResolver().insert(DatabaseContract.LeagueEntry.CONTENT_URI,values);
                c = getContentResolver().query(newItemUri,null,null,null,null);
            }
        }

        if (c.moveToFirst()) {
            String leagueName = c.getString(c.getColumnIndex(DatabaseContract.LeagueEntry.NAME_COL));
            v.put(DatabaseContract.ScoresEntry.LEAGUE_COL, leagueName);
            v.put(DatabaseContract.ScoresEntry.LEAGUE_ID_COL, leagueId);
            Log.i(TAG, "League: " + leagueName + ", " + leagueId);
        }else{
            Log.e(TAG, "Empty response for league");
            v.put(DatabaseContract.ScoresEntry.LEAGUE_COL, "League info not available");
            v.put(DatabaseContract.ScoresEntry.LEAGUE_ID_COL, -1);
        }
        c.close();
    }

    private void getData (String timeFrame)
    {
        //Creating fetch URL
        final String BASE_URL = "http://api.football-data.org/alpha/fixtures"; //Base URL
        final String QUERY_TIME_FRAME = "timeFrame"; //Time Frame parameter to determine days
        //final String QUERY_MATCH_DAY = "matchday";

        Uri fetch_build = Uri.parse(BASE_URL).buildUpon().
                appendQueryParameter(QUERY_TIME_FRAME, timeFrame).build();
        //Log.v(TAG, "The url we are looking at is: "+fetch_build.toString()); //log spam
        HttpURLConnection m_connection = null;
        BufferedReader reader = null;
        String JSON_data = null;
        //Opening Connection
        try {
            URL fetch = new URL(fetch_build.toString());
            m_connection = (HttpURLConnection) fetch.openConnection();
            m_connection.setRequestMethod("GET");
            m_connection.addRequestProperty("X-Auth-Token",getString(R.string.api_key));
            m_connection.connect();

            // Read the input stream into a String
            InputStream inputStream = m_connection.getInputStream();
            StringBuffer buffer = new StringBuffer();
            if (inputStream == null) {
                // Nothing to do.
                return;
            }
            reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            while ((line = reader.readLine()) != null) {
                // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                // But it does make debugging a *lot* easier if you print out the completed
                // buffer for debugging.
                buffer.append(line + "\n");
            }
            if (buffer.length() == 0) {
                // Stream was empty.  No point in parsing.
                return;
            }
            JSON_data = buffer.toString();
        }
        catch (Exception e)
        {
            Log.e(TAG,"Exception here" + e.getMessage());
        }
        finally {
            if(m_connection != null)
            {
                m_connection.disconnect();
            }
            if (reader != null)
            {
                try {
                    reader.close();
                }
                catch (IOException e)
                {
                    Log.e(TAG,"Error Closing Stream");
                }
            }
        }
        try {
            if (JSON_data != null) {
                //This bit is to check if the data contains any matches. If not, we call processJson on the dummy data
                JSONArray matches = new JSONObject(JSON_data).getJSONArray("fixtures");
                if (matches.length() == 0) {
                    //if there is no data, call the function on dummy data
                    //this is expected behavior during the off season.
                    processJSONdata(getString(R.string.dummy_data), getApplicationContext(), false);
                    return;
                }


                processJSONdata(JSON_data, getApplicationContext(), true);
            } else {
                //Could not Connect
                Log.d(TAG, "Could not connect to server.");
            }
        }
        catch(Exception e)
        {
            Log.e(TAG,e.getMessage());
        }
    }
    private void processJSONdata (String JSONdata,Context mContext, boolean isReal)
    {
        //JSON data
        // This set of league codes is for the 2015/2016 season. In fall of 2016, they will need to
        // be updated. Feel free to use the codes
        final String BUNDESLIGA1 = "394";
        final String BUNDESLIGA2 = "395";
        final String LIGUE1 = "396";
        final String LIGUE2 = "397";
        final String PREMIER_LEAGUE = "398";
        final String PRIMERA_DIVISION = "399";
        final String SEGUNDA_DIVISION = "400";
        final String SERIE_A = "401";
        final String PRIMERA_LIGA = "402";
        final String Bundesliga3 = "403";
        final String EREDIVISIE = "404";


        final String SEASON_LINK = "http://api.football-data.org/alpha/soccerseasons/";
        final String MATCH_LINK = "http://api.football-data.org/alpha/fixtures/";
        final String FIXTURES = "fixtures";
        final String LINKS = "_links";
        final String SOCCER_SEASON = "soccerseason";
        final String SELF = "self";
        final String MATCH_DATE = "date";
        final String HOME_TEAM = "homeTeamName";
        final String AWAY_TEAM = "awayTeamName";
        final String RESULT = "result";
        final String HOME_GOALS = "goalsHomeTeam";
        final String AWAY_GOALS = "goalsAwayTeam";
        final String MATCH_DAY = "matchday";

        //Match data
        String League = null;
        String mDate = null;
        String mTime = null;
        String Home = null;
        String Away = null;
        String Home_goals = null;
        String Away_goals = null;
        String match_id = null;
        String match_day = null;


        try {
            JSONArray matches = new JSONObject(JSONdata).getJSONArray(FIXTURES);


            //ContentValues to be inserted
            Vector<ContentValues> values = new Vector <ContentValues> (matches.length());
            for(int i = 0;i < matches.length();i++)
            {

                JSONObject match_data = matches.getJSONObject(i);
                League = match_data.getJSONObject(LINKS).getJSONObject(SOCCER_SEASON).
                        getString("href");
                League = League.replace(SEASON_LINK,"");
                //This if statement controls which leagues we're interested in the data from.
                //add leagues here in order to have them be added to the DB.
                // If you are finding no data in the app, check that this contains all the leagues.
                // If it doesn't, that can cause an empty DB, bypassing the dummy data routine.
                if(     League.equals(PREMIER_LEAGUE)      ||
                        League.equals(SERIE_A)             ||
                        League.equals(BUNDESLIGA1)         ||
                        League.equals(BUNDESLIGA2)         ||
                        League.equals(PRIMERA_DIVISION)     )
                {
                    match_id = match_data.getJSONObject(LINKS).getJSONObject(SELF).
                            getString("href");
                    match_id = match_id.replace(MATCH_LINK, "");
                    if(!isReal){
                        //This if statement changes the match ID of the dummy data so that it all goes into the database
                        match_id=match_id+Integer.toString(i);
                    }

                    mDate = match_data.getString(MATCH_DATE);
                    mTime = mDate.substring(mDate.indexOf("T") + 1, mDate.indexOf("Z"));
                    mDate = mDate.substring(0,mDate.indexOf("T"));
                    SimpleDateFormat match_date = new SimpleDateFormat("yyyy-MM-ddHH:mm:ss");
                    match_date.setTimeZone(TimeZone.getTimeZone("UTC"));
                    try {
                        Date parseddate = match_date.parse(mDate+mTime);
                        SimpleDateFormat new_date = new SimpleDateFormat("yyyy-MM-dd:HH:mm");
                        new_date.setTimeZone(TimeZone.getDefault());
                        mDate = new_date.format(parseddate);
                        mTime = mDate.substring(mDate.indexOf(":") + 1);
                        mDate = mDate.substring(0,mDate.indexOf(":"));

                        if(!isReal){
                            //This if statement changes the dummy data's date to match our current date range.
                            Date fragmentdate = new Date(System.currentTimeMillis()+((i-2)*86400000));
                            SimpleDateFormat mformat = new SimpleDateFormat("yyyy-MM-dd");
                            mDate=mformat.format(fragmentdate);
                        }
                    }
                    catch (Exception e)
                    {
                        Log.d(TAG, "error here!");
                        Log.e(TAG,e.getMessage());
                    }
                    Home = match_data.getString(HOME_TEAM);
                    Away = match_data.getString(AWAY_TEAM);
                    Home_goals = match_data.getJSONObject(RESULT).getString(HOME_GOALS);
                    Away_goals = match_data.getJSONObject(RESULT).getString(AWAY_GOALS);
                    match_day = match_data.getString(MATCH_DAY);
                    ContentValues match_values = new ContentValues();
                    match_values.put(DatabaseContract.ScoresEntry.MATCH_ID,match_id);
                    match_values.put(DatabaseContract.ScoresEntry.DATE_COL,mDate);
                    match_values.put(DatabaseContract.ScoresEntry.TIME_COL,mTime);
                    match_values.put(DatabaseContract.ScoresEntry.HOME_COL,Home);
                    match_values.put(DatabaseContract.ScoresEntry.AWAY_COL,Away);
                    match_values.put(DatabaseContract.ScoresEntry.HOME_GOALS_COL,Home_goals);
                    match_values.put(DatabaseContract.ScoresEntry.AWAY_GOALS_COL,Away_goals);
                    match_values.put(DatabaseContract.ScoresEntry.LEAGUE_COL,League);
                    match_values.put(DatabaseContract.ScoresEntry.MATCH_DAY,match_day);
                    //log spam

                    //Log.v(TAG,matchId);
                    //Log.v(TAG,mDate);
                    //Log.v(TAG,mTime);
                    //Log.v(TAG,Home);
                    //Log.v(TAG,Away);
                    //Log.v(TAG,Home_goals);
                    //Log.v(TAG,Away_goals);

                    values.add(match_values);
                }
            }
            int inserted_data = 0;
            ContentValues[] insert_data = new ContentValues[values.size()];
            values.toArray(insert_data);
            inserted_data = mContext.getContentResolver().bulkInsert(
                    DatabaseContract.BASE_CONTENT_URI,insert_data);

            //Log.v(TAG,"Succesfully Inserted : " + String.valueOf(inserted_data));
        }
        catch (JSONException e)
        {
            Log.e(TAG,e.getMessage());
        }

    }
}

