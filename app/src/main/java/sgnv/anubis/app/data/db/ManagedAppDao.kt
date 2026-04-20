package sgnv.anubis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.ManagedApp

@Dao
interface ManagedAppDao {

    @Query("SELECT * FROM managed_apps WHERE `group` = :group")
    suspend fun getByGroup(group: AppGroup): List<ManagedApp>

    @Query("SELECT packageName FROM managed_apps WHERE `group` = :group")
    suspend fun getPackageNamesByGroup(group: AppGroup): List<String>

    @Query("SELECT * FROM managed_apps WHERE packageName = :packageName")
    suspend fun get(packageName: String): ManagedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: ManagedApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<ManagedApp>)

    @Query("DELETE FROM managed_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
